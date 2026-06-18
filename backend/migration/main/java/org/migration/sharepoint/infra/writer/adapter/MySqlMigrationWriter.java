/*
 * SPDX-License-Identifier: BSD-3-Clause
 *
 * Copyright (c) 2026 Vinícius Gabriel Pereira Leitão
 * Licensed under the BSD 3-Clause License.
 * See LICENSE file in the project root for full license information.
 */
package org.migration.sharepoint.infra.writer.adapter;

import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.migration.sharepoint.data.enums.ColumnType;
import org.migration.sharepoint.data.enums.TargetDb;
import org.migration.sharepoint.data.model.FieldMapping;
import org.migration.sharepoint.data.model.ForeignKeyDefinition;
import org.migration.sharepoint.infra.exception.ErrorCode;
import org.migration.sharepoint.infra.exception.custom.BadRequestException;
import org.migration.sharepoint.infra.exception.custom.InfrastructureException;
import org.migration.sharepoint.infra.writer.MigrationWriter;
import org.migration.sharepoint.infra.writer.NativeTypeDefinition;
import org.migration.sharepoint.infra.writer.NativeTypeDefinition.ParamSpec;
import org.migration.sharepoint.infra.writer.WriterConnectionPool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MySqlMigrationWriter implements MigrationWriter {

    private static final ZoneId ZONE_BR = ZoneId.of("America/Sao_Paulo");
    private static final Map<ColumnType, String> MYSQL_CANONICAL_MAP = Map.of(
            ColumnType.TEXT, "LONGTEXT",
            ColumnType.NUMBER, "BIGINT",
            ColumnType.INTEGER, "INT",
            ColumnType.DECIMAL, "DECIMAL(19,4)",
            ColumnType.BOOLEAN, "TINYINT(1)",
            ColumnType.DATE, "DATE",
            ColumnType.DATETIME, "DATETIME(3)");

    private static final List<NativeTypeDefinition> MYSQL_TYPE_DEFS = List.of(
            new NativeTypeDefinition("TINYINT", List.of()),
            new NativeTypeDefinition("SMALLINT", List.of()),
            new NativeTypeDefinition("INT", List.of()),
            new NativeTypeDefinition("BIGINT", List.of()),
            new NativeTypeDefinition("FLOAT", List.of()),
            new NativeTypeDefinition("DOUBLE", List.of()),
            new NativeTypeDefinition("DECIMAL", List.of(new ParamSpec("M", 1, 65), new ParamSpec("D", 0, 30))),
            new NativeTypeDefinition("VARCHAR", List.of(new ParamSpec("N", 1, 65535))),
            new NativeTypeDefinition("TEXT", List.of()),
            new NativeTypeDefinition("DATE", List.of()),
            new NativeTypeDefinition("DATETIME", List.of()));

    @Value("${writer.batch-size:500}")
    private int batchSize;

    private final WriterConnectionPool connectionPool;

    @Override
    public boolean supports(TargetDb targetDb) {
        return targetDb == TargetDb.MYSQL;
    }

    @Override
    public List<NativeTypeDefinition> typeDefinitions() {
        return MYSQL_TYPE_DEFS;
    }

    @Override
    public Map<ColumnType, String> canonicalMapping() {
        return MYSQL_CANONICAL_MAP;
    }

    @Override
    public List<Object> write(
            String key,
            String table,
            List<Map<String, Object>> rows,
            Map<String, FieldMapping> types,
            List<ForeignKeyDefinition> fks,
            String parent) {
        validateTableName(table);
        return withConnection(key, connection -> {
            syncSchema(connection, connection.getCatalog(), table, types, fks, parent);
            if (rows.isEmpty()) return List.of();

            List<String> tableCols = fetchTableColumns(connection, connection.getCatalog(), table);
            List<Map<String, Object>> validRows = filterRows(rows, tableCols);

            // Ensure we have at least one row with data to determine columns
            if (validRows.isEmpty()) return List.of();

            List<String> insertCols = tableCols.stream()
                    .filter(column -> validRows.getFirst().containsKey(column))
                    .toList();

            return executeTransaction(
                    connection, table, () -> batchUpsert(connection, table, insertCols, validRows, types));
        });
    }

    private <T> T withConnection(String key, ConnectionFunction<T> function) {
        try (Connection connection = connectionPool.getConnection(key)) {
            return function.apply(connection);
        } catch (SQLException exception) {
            log.error("Erro de conexão com o banco {}: {}", key, exception.getMessage());
            throw new InfrastructureException(ErrorCode.DB_CONNECTION_ERROR, "Erro MySQL: " + exception.getMessage());
        }
    }

    private <T> T executeTransaction(Connection connection, String table, TransactionSupplier<T> supplier)
            throws SQLException {
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET FOREIGN_KEY_CHECKS = 0");
            try {
                T result = supplier.get();
                connection.commit();
                return result;
            } catch (Exception e) {
                connection.rollback();
                throw e;
            } finally {
                statement.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        }
    }

    private List<Object> batchUpsert(
            Connection connection,
            String table,
            List<String> columns,
            List<Map<String, Object>> rows,
            Map<String, FieldMapping> types)
            throws SQLException {
        String primaryKey = findPrimaryKey(types);
        String sql = buildUpsertSql(connection, table, columns, primaryKey);
        List<Object> keys = new ArrayList<>();

        try (PreparedStatement preparedStatement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            partition(rows, batchSize).forEach(batch -> {
                try {
                    for (Map<String, Object> row : batch) {
                        setParams(preparedStatement, columns, row, types);
                        preparedStatement.addBatch();
                    }
                    preparedStatement.executeBatch();
                    collectKeys(preparedStatement, keys);
                    preparedStatement.clearBatch();
                } catch (SQLException exception) {
                    log.error("Erro no batch insert na tabela {}: {}", table, exception.getMessage());
                    throw new RuntimeException(exception);
                }
            });
        } catch (RuntimeException runtimeException) {
            if (runtimeException.getCause() instanceof SQLException sqlException) throw sqlException;
            throw runtimeException;
        }
        return keys;
    }

    private String buildUpsertSql(Connection connection, String table, List<String> columns, String primaryKey)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            String colList =
                    columns.stream().map(column -> enquote(statement, column)).collect(Collectors.joining(","));
            String placeholders =
                    Stream.generate(() -> "?").limit(columns.size()).collect(Collectors.joining(","));
            String baseInsert =
                    "INSERT INTO %s (%s) VALUES (%s)".formatted(enquote(statement, table), colList, placeholders);

            return Optional.ofNullable(primaryKey)
                    .map(pk -> baseInsert
                            + " ON DUPLICATE KEY UPDATE %s = LAST_INSERT_ID(%s)"
                                    .formatted(enquote(statement, pk), enquote(statement, pk)))
                    .orElse(baseInsert);
        }
    }

    private void setParams(
            PreparedStatement preparedStatement,
            List<String> columns,
            Map<String, Object> row,
            Map<String, FieldMapping> types) {
        IntStream.range(0, columns.size()).forEach(index -> {
            try {
                String column = columns.get(index);
                Object value = row.get(column);
                preparedStatement.setObject(index + 1, convert(value, types.get(column)));
            } catch (SQLException exception) {
                throw new RuntimeException(exception);
            }
        });
    }

    Object convert(Object value, FieldMapping mapping) {
        if (value == null) return null;

        // Handle empty strings for numeric/date types by returning NULL
        if (value instanceof String str && str.trim().isEmpty()) {
            if (mapping.getType() == ColumnType.NUMBER
                    || mapping.getType() == ColumnType.INTEGER
                    || mapping.getType() == ColumnType.DECIMAL
                    || mapping.getType() == ColumnType.DATE
                    || mapping.getType() == ColumnType.DATETIME) {
                return null;
            }
        }

        return switch (mapping.getType()) {
            case BOOLEAN -> handleBool(value);
            case NUMBER, INTEGER -> handleNum(value);
            case DATE, DATETIME -> handleDate(value, mapping);
            default -> value;
        };
    }

    private Object handleBool(Object value) {
        if (value instanceof Boolean bool) return bool ? 1 : 0;
        String str = String.valueOf(value).trim();
        return Stream.of("true", "1", "yes").anyMatch(s -> s.equalsIgnoreCase(str)) ? 1 : 0;
    }

    private Object handleNum(Object value) {
        if (value instanceof Number num) return num.longValue();
        if (value instanceof String str) {
            try {
                return Long.parseLong(str.trim());
            } catch (NumberFormatException e) {
                return null; // Return null instead of invalid string
            }
        }
        return value;
    }

    private Object handleDate(Object value, FieldMapping mapping) {
        try {
            LocalDateTime dateTime = resolveDateTime(value);
            if (dateTime == null) return null;
            String pattern = isFullDateTime(mapping) ? "yyyy-MM-dd HH:mm:ss" : "yyyy-MM-dd";
            return dateTime.format(DateTimeFormatter.ofPattern(pattern));
        } catch (Exception exception) {
            return null;
        }
    }

    private LocalDateTime resolveDateTime(Object value) {
        if (value instanceof String str) {
            try {
                return LocalDateTime.ofInstant(Instant.parse(str), ZONE_BR);
            } catch (Exception e) {
                return null;
            }
        }
        if (value instanceof LocalDateTime ldt) return ldt;
        if (value instanceof LocalDate ld) return ld.atStartOfDay();
        return null;
    }

    private boolean isFullDateTime(FieldMapping mapping) {
        return mapping.getType() == ColumnType.DATETIME
                || Optional.ofNullable(mapping.getNativeType())
                        .map(nativeType -> nativeType.toUpperCase().contains("TIME"))
                        .orElse(false);
    }

    private void syncSchema(
            Connection connection,
            String catalog,
            String table,
            Map<String, FieldMapping> types,
            List<ForeignKeyDefinition> foreignKeys,
            String parent)
            throws SQLException {
        if (!tableExists(connection, catalog, table)) {
            createTable(connection, table, types, foreignKeys, parent);
        } else {
            updateSchema(connection, catalog, table, types);
        }
    }

    private void createTable(
            Connection connection,
            String table,
            Map<String, FieldMapping> types,
            List<ForeignKeyDefinition> foreignKeys,
            String parent)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            List<String> definitions = new ArrayList<>();

            // Columns
            types.forEach((name, mapping) -> definitions.add(formatColumn(statement, name, mapping)));

            // Primary Key
            types.values().stream()
                    .filter(FieldMapping::isPrimaryKey)
                    .map(mapping -> "PRIMARY KEY (" + enquote(statement, mapping.getColumn()) + ")")
                    .findFirst()
                    .ifPresent(definitions::add);

            // Unique Keys
            types.values().stream()
                    .filter(mapping -> mapping.isUniqueKey() && !mapping.isPrimaryKey())
                    .forEach(mapping -> definitions.add(
                            "UNIQUE KEY " + enquote(statement, "uk_" + table + "_" + mapping.getColumn())
                                    + " ("
                                    + enquote(statement, mapping.getColumn()) + ")"));

            // Foreign Keys
            if (parent != null && foreignKeys != null) {
                foreignKeys.forEach(
                        fk -> definitions.add("CONSTRAINT " + enquote(statement, "fk_" + table + "_" + parent)
                                + " FOREIGN KEY ("
                                + enquote(statement, fk.getLocalColumn()) + ") " + "REFERENCES "
                                + enquote(statement, parent) + " (" + enquote(statement, fk.getParentColumn()) + ")"));
            }

            String sql = "CREATE TABLE %s (%s) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci"
                    .formatted(enquote(statement, table), String.join(", ", definitions));

            log.debug("Criando tabela {}: {}", table, sql);
            statement.execute(sql);
        }
    }

    private void updateSchema(Connection connection, String catalog, String table, Map<String, FieldMapping> types)
            throws SQLException {
        List<String> existingColumns = fetchTableColumns(connection, catalog, table);
        try (Statement statement = connection.createStatement()) {
            types.entrySet().stream()
                    .filter(entry -> !existingColumns.contains(entry.getKey()))
                    .forEach(entry -> {
                        String sql = "ALTER TABLE %s ADD COLUMN %s %s"
                                .formatted(
                                        enquote(statement, table),
                                        enquote(statement, entry.getKey()),
                                        resolveType(entry.getValue()));
                        executeUnchecked(statement, sql);
                    });
        }
    }

    private String formatColumn(Statement statement, String name, FieldMapping mapping) {
        return "%s %s%s%s"
                .formatted(
                        enquote(statement, name),
                        resolveType(mapping),
                        mapping.isPrimaryKey() ? " NOT NULL" : " DEFAULT NULL",
                        mapping.isAutoIncrement() ? " AUTO_INCREMENT" : "");
    }

    String resolveType(FieldMapping mapping) {
        return Optional.ofNullable(mapping.getNativeType())
                .filter(nativeType -> !nativeType.isBlank())
                .map(nativeType -> nativeType
                        .toUpperCase()
                        .replace("NOT NULL", "")
                        .replace("AUTO_INCREMENT", "")
                        .trim())
                .orElseGet(() -> {
                    String base = MYSQL_CANONICAL_MAP.getOrDefault(mapping.getType(), "TEXT");
                    return (mapping.isPrimaryKey() && base.toUpperCase().contains("TEXT")) ? "VARCHAR(255)" : base;
                });
    }

    private void collectKeys(PreparedStatement preparedStatement, List<Object> keys) throws SQLException {
        try (ResultSet resultSet = preparedStatement.getGeneratedKeys()) {
            while (resultSet.next()) keys.add(resultSet.getLong(1));
        }
    }

    private boolean tableExists(Connection connection, String catalog, String table) throws SQLException {
        try (PreparedStatement preparedStatement = connection.prepareStatement(
                "SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=? AND TABLE_NAME=?")) {
            preparedStatement.setString(1, catalog);
            preparedStatement.setString(2, table);
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private List<String> fetchTableColumns(Connection connection, String catalog, String table) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (PreparedStatement preparedStatement = connection.prepareStatement(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=?")) {
            preparedStatement.setString(1, catalog);
            preparedStatement.setString(2, table);
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                while (resultSet.next()) columns.add(resultSet.getString(1));
            }
        }
        return columns;
    }

    private List<Map<String, Object>> filterRows(List<Map<String, Object>> rows, List<String> tableCols) {
        return rows.stream()
                .map(row -> {
                    Map<String, Object> filteredRow = new LinkedHashMap<>();
                    row.forEach((key, value) -> {
                        if (tableCols.contains(key)) {
                            filteredRow.put(key, value);
                        }
                    });
                    return filteredRow;
                })
                .filter(row -> !row.isEmpty())
                .collect(Collectors.toList());
    }

    private String enquote(Statement statement, String identifier) {
        try {
            return statement.enquoteIdentifier(identifier, true);
        } catch (SQLException exception) {
            return identifier;
        }
    }

    private void validateTableName(String name) {
        if (!name.matches("[a-zA-Z0-9_]+")) throw new BadRequestException(ErrorCode.BAD_REQUEST, "Tabela inválida");
    }

    private String findPrimaryKey(Map<String, FieldMapping> types) {
        return types.values().stream()
                .filter(FieldMapping::isPrimaryKey)
                .map(FieldMapping::getColumn)
                .findFirst()
                .orElse(null);
    }

    private void executeUnchecked(Statement statement, String sql) {
        try {
            statement.execute(sql);
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    private <T> Stream<List<T>> partition(List<T> list, int size) {
        if (list.isEmpty()) return Stream.empty();
        return IntStream.iterate(0, index -> index < list.size(), index -> index + size)
                .mapToObj(index -> list.subList(index, Math.min(index + size, list.size())));
    }

    @FunctionalInterface
    interface ConnectionFunction<T> {
        T apply(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    interface TransactionSupplier<T> {
        T get() throws SQLException;
    }
}
