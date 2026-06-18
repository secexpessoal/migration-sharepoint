/*
 * SPDX-License-Identifier: BSD-3-Clause
 *
 * Copyright (c) 2026 Vinícius Gabriel Pereira Leitão
 * Licensed under the BSD 3-Clause License.
 * See LICENSE file in the project root for full license information.
 */
package org.migration.sharepoint.infra.writer.adapter;

import com.mongodb.ConnectionString;
import com.mongodb.MongoBulkWriteException;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.migration.sharepoint.data.enums.ColumnType;
import org.migration.sharepoint.data.enums.TargetDb;
import org.migration.sharepoint.data.model.FieldMapping;
import org.migration.sharepoint.data.model.ForeignKeyDefinition;
import org.migration.sharepoint.infra.connection.ConnectionRegistry;
import org.migration.sharepoint.infra.exception.ErrorCode;
import org.migration.sharepoint.infra.exception.custom.BadRequestException;
import org.migration.sharepoint.infra.exception.custom.InfrastructureException;
import org.migration.sharepoint.infra.writer.MigrationWriter;
import org.migration.sharepoint.infra.writer.NativeTypeDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MongoDbMigrationWriter implements MigrationWriter {

    private static final ZoneId ZONE_BR = ZoneId.of("America/Sao_Paulo");
    private static final String SEQUENCE_COLLECTION = "migration_sequences";

    private static final Map<ColumnType, String> MONGO_CANONICAL_MAP = Map.of(
            ColumnType.TEXT, "String",
            ColumnType.NUMBER, "Int64",
            ColumnType.INTEGER, "Int32",
            ColumnType.DECIMAL, "Decimal128",
            ColumnType.BOOLEAN, "Boolean",
            ColumnType.DATE, "Date",
            ColumnType.DATETIME, "Date");

    private static final List<NativeTypeDefinition> MONGO_TYPE_DEFS = List.of(
            new NativeTypeDefinition("String", List.of()),
            new NativeTypeDefinition("Int32", List.of()),
            new NativeTypeDefinition("Int64", List.of()),
            new NativeTypeDefinition("Double", List.of()),
            new NativeTypeDefinition("Decimal128", List.of()),
            new NativeTypeDefinition("Boolean", List.of()),
            new NativeTypeDefinition("Date", List.of()),
            new NativeTypeDefinition("ObjectId", List.of()),
            new NativeTypeDefinition("Document", List.of()),
            new NativeTypeDefinition("Array", List.of()));

    @Value("${writer.batch-size:500}")
    private int batchSize;

    private final ConnectionRegistry connectionRegistry;

    @Override
    public boolean supports(TargetDb targetDb) {
        return targetDb == TargetDb.MONGODB;
    }

    @Override
    public List<NativeTypeDefinition> typeDefinitions() {
        return MONGO_TYPE_DEFS;
    }

    @Override
    public Map<ColumnType, String> canonicalMapping() {
        return MONGO_CANONICAL_MAP;
    }

    @Override
    public List<Object> write(
            String connectionKey,
            String targetName,
            List<Map<String, Object>> rows,
            Map<String, FieldMapping> columnTypes,
            List<ForeignKeyDefinition> foreignKeys,
            String parentTableName) {
        validateCollectionName(targetName);
        validateFieldNames(columnTypes);

        return withDatabase(connectionKey, database -> {
            MongoCollection<Document> collection = database.getCollection(targetName);
            syncCollection(collection, targetName, columnTypes, foreignKeys);
            if (rows.isEmpty()) return List.of();

            List<Document> documents = rows.stream()
                    .map(row -> toDocument(row, columnTypes))
                    .filter(document -> !document.isEmpty())
                    .toList();

            if (documents.isEmpty()) return List.of();

            return batchUpsert(database, collection, targetName, documents, columnTypes, uniqueIndexes(collection));
        });
    }

    private <T> T withDatabase(String key, DatabaseFunction<T> function) {
        String mongoUrl = connectionRegistry.resolveUrl(key);

        try {
            ConnectionString connectionString = new ConnectionString(mongoUrl);
            String databaseName = Optional.ofNullable(connectionString.getDatabase())
                    .filter(name -> !name.isBlank())
                    .orElseThrow(() -> new BadRequestException(
                            ErrorCode.BAD_REQUEST,
                            "URL MongoDB da conexão '%s' deve informar o database".formatted(key)));

            log.debug(
                    "Conectando MongoDB key={} database={} topology={}",
                    key,
                    databaseName,
                    resolveTopology(mongoUrl, connectionString));

            try (MongoClient client = MongoClients.create(connectionString)) {
                return function.apply(client.getDatabase(databaseName));
            }
        } catch (BadRequestException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException(
                    ErrorCode.BAD_REQUEST, "URL MongoDB inválida para conexão '%s'".formatted(key));
        } catch (MongoException exception) {
            log.error("Erro de conexão/operação MongoDB {}: {}", key, exception.getMessage());
            throw new InfrastructureException(ErrorCode.DB_CONNECTION_ERROR, "Erro MongoDB: " + exception.getMessage());
        }
    }

    private void syncCollection(
            MongoCollection<Document> collection,
            String collectionName,
            Map<String, FieldMapping> types,
            List<ForeignKeyDefinition> foreignKeys) {
        types.values().stream()
                .filter(mapping ->
                        (mapping.isPrimaryKey() || mapping.isUniqueKey()) && !"_id".equals(mapping.getColumn()))
                .forEach(mapping -> ensureIndex(
                        collection,
                        new Document(mapping.getColumn(), 1),
                        new IndexOptions().unique(true).name(indexName("uk", collectionName, mapping.getColumn()))));

        Optional.ofNullable(foreignKeys).orElse(List.of()).stream()
                .map(ForeignKeyDefinition::getLocalColumn)
                .filter(column -> column != null && !column.isBlank() && !"_id".equals(column))
                .forEach(column -> ensureIndex(
                        collection,
                        new Document(column, 1),
                        new IndexOptions().name(indexName("idx", collectionName, column))));
    }

    private void ensureIndex(MongoCollection<Document> collection, Document keys, IndexOptions options) {
        if (indexWithSameKeysExists(collection, keys)) return;

        try {
            collection.createIndex(keys, options);
        } catch (MongoCommandException exception) {
            if (isIndexAlreadyPresent(exception)) {
                log.debug(
                        "Índice MongoDB já existe na collection={} keys={} code={}",
                        collection.getNamespace().getCollectionName(),
                        keys.toJson(),
                        exception.getErrorCode());
                return;
            }
            throw exception;
        }
    }

    private boolean indexWithSameKeysExists(MongoCollection<Document> collection, Document keys) {
        for (Document index : collection.listIndexes()) {
            if (keys.equals(index.get("key", Document.class))) return true;
        }
        return false;
    }

    private boolean isIndexAlreadyPresent(MongoCommandException exception) {
        return exception.getErrorCode() == 85 || exception.getErrorCode() == 86;
    }

    private List<Object> batchUpsert(
            MongoDatabase database,
            MongoCollection<Document> collection,
            String collectionName,
            List<Document> documents,
            Map<String, FieldMapping> types,
            List<UniqueIndexDefinition> uniqueIndexes) {
        List<WriteModel<Document>> writes = new ArrayList<>();
        List<Object> keys = new ArrayList<>();
        List<List<FilterCandidate>> candidatesByDocument = documents.stream()
                .map(document -> buildFilterCandidates(document, types, uniqueIndexes))
                .toList();
        Map<String, Object> seenKeys = new HashMap<>(fetchExistingIds(collection, candidatesByDocument));

        IntStream.range(0, documents.size()).forEach(index -> {
            Document document = documents.get(index);
            List<FilterCandidate> candidates = candidatesByDocument.get(index);
            Optional<FilterCandidate> alreadySeen = candidates.stream()
                    .filter(candidate -> seenKeys.containsKey(candidate.key()))
                    .findFirst();

            if (alreadySeen.isPresent()) {
                document.put("_id", seenKeys.get(alreadySeen.get().key()));
                Optional.ofNullable(resolveReturnedKey(document, types)).ifPresent(keys::add);
                return;
            }

            ensureDocumentId(document);
            Bson filter = firstFilter(candidates);
            applyAutoIncrement(database, collection, collectionName, document, types, filter);

            Bson insertFilter = firstFilter(candidates);
            if (insertFilter == null) {
                writes.add(new InsertOneModel<>(document));
            } else {
                writes.add(new UpdateOneModel<>(
                        insertFilter, new Document("$setOnInsert", document), new UpdateOptions().upsert(true)));
            }

            Optional.ofNullable(resolveReturnedKey(document, types)).ifPresent(keys::add);
            rememberSeenKeys(candidates, document, types, seenKeys);
        });

        partition(writes, batchSize).forEach(batch -> {
            try {
                collection.bulkWrite(batch, new BulkWriteOptions().ordered(false));
            } catch (MongoBulkWriteException exception) {
                if (exception.getWriteErrors().stream().allMatch(error -> error.getCode() == 11000)) return;
                throw exception;
            }
        });
        return keys;
    }

    private Map<String, Object> fetchExistingIds(
            MongoCollection<Document> collection, List<List<FilterCandidate>> candidatesByDocument) {
        Map<String, FilterCandidate> candidatesByKey = new LinkedHashMap<>();
        candidatesByDocument.stream()
                .flatMap(Collection::stream)
                .forEach(candidate -> candidatesByKey.putIfAbsent(candidate.key(), candidate));

        if (candidatesByKey.isEmpty()) return Map.of();

        Map<String, Object> existingIds = new HashMap<>();
        partition(new ArrayList<>(candidatesByKey.values()), batchSize).forEach(batch -> {
            List<Bson> filters = batch.stream().map(FilterCandidate::filter).toList();
            Map<String, FilterCandidate> batchCandidates = batch.stream()
                    .collect(java.util.stream.Collectors.toMap(FilterCandidate::key, candidate -> candidate));

            collection
                    .find(Filters.or(filters))
                    .forEach(existing -> batch.forEach(candidate -> {
                        String key = buildCandidateKey(existing, candidate.fields());
                        if (batchCandidates.containsKey(key)) {
                            existingIds.put(key, existing.get("_id"));
                        }
                    }));
        });
        return existingIds;
    }

    private Bson firstFilter(List<FilterCandidate> candidates) {
        return candidates.isEmpty() ? null : candidates.getFirst().filter();
    }

    private void rememberSeenKeys(
            List<FilterCandidate> candidates,
            Document document,
            Map<String, FieldMapping> types,
            Map<String, Object> seenKeys) {
        Object returnedKey = resolveReturnedKey(document, types);
        if (returnedKey == null) return;
        candidates.forEach(candidate -> seenKeys.put(candidate.key(), returnedKey));
    }

    private void ensureDocumentId(Document document) {
        if (document.get("_id") != null) return;
        document.put("_id", new ObjectId());
    }

    private void applyAutoIncrement(
            MongoDatabase database,
            MongoCollection<Document> collection,
            String collectionName,
            Document document,
            Map<String, FieldMapping> types,
            Bson filter) {
        autoIncrementMappings(types).forEach(mapping -> {
            String column = mapping.getColumn();
            if (document.get(column) != null) return;

            Object existingValue = findExistingValue(collection, filter, column);
            document.put(
                    column,
                    existingValue == null
                            ? nextSequence(database, collectionName, column)
                            : normalizeNumber(existingValue));
        });
    }

    private Object findExistingValue(MongoCollection<Document> collection, Bson filter, String column) {
        if (filter == null) return null;
        Document existing = collection.find(filter).first();
        return existing == null ? null : existing.get(column);
    }

    private Long nextSequence(MongoDatabase database, String collectionName, String column) {
        MongoCollection<Document> sequences = database.getCollection(SEQUENCE_COLLECTION);
        Document sequence = sequences.findOneAndUpdate(
                Filters.eq("_id", "%s.%s".formatted(collectionName, column)),
                Updates.inc("value", 1L),
                new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));

        Object value = sequence == null ? null : sequence.get("value");
        return value instanceof Number number ? number.longValue() : 1L;
    }

    Document toDocument(Map<String, Object> row, Map<String, FieldMapping> types) {
        Document document = new Document();
        types.forEach((column, mapping) -> {
            if (row.containsKey(column)) {
                document.put(column, convert(row.get(column), mapping));
            }
        });
        return document;
    }

    Object convert(Object value, FieldMapping mapping) {
        if (value == null) return null;

        ColumnType columnType = Optional.ofNullable(mapping.getType()).orElse(ColumnType.TEXT);
        if (value instanceof String str && str.trim().isEmpty() && isNullableBlankType(columnType)) {
            return null;
        }

        String type = resolveType(mapping);
        return switch (type) {
            case "String" -> String.valueOf(value);
            case "Int32" -> toInteger(value);
            case "Int64" -> toLong(value);
            case "Double" -> toDouble(value);
            case "Decimal128" -> toDecimal(value);
            case "Boolean" -> toBoolean(value);
            case "Date" -> toDate(value);
            case "ObjectId" -> toObjectId(value);
            case "Document" -> toDocumentValue(value);
            case "Array" -> toArrayValue(value);
            default -> value;
        };
    }

    String resolveType(FieldMapping mapping) {
        return Optional.ofNullable(mapping.getNativeType())
                .filter(nativeType -> !nativeType.isBlank())
                .map(this::normalizeTypeName)
                .orElseGet(() -> MONGO_CANONICAL_MAP.getOrDefault(
                        Optional.ofNullable(mapping.getType()).orElse(ColumnType.TEXT), "String"));
    }

    List<FilterCandidate> buildFilterCandidates(
            Document document, Map<String, FieldMapping> types, List<UniqueIndexDefinition> uniqueIndexes) {
        List<FilterCandidate> candidates = new ArrayList<>();
        Set<List<String>> seenFields = new HashSet<>();

        findPrimaryKey(types)
                .map(FieldMapping::getColumn)
                .filter(column -> document.get(column) != null)
                .ifPresent(column -> addFilterCandidate(candidates, seenFields, document, List.of(column)));

        uniqueIndexes.stream()
                .map(UniqueIndexDefinition::fields)
                .filter(fields -> fields.stream().allMatch(column -> document.get(column) != null))
                .forEach(fields -> addFilterCandidate(candidates, seenFields, document, fields));

        types.values().stream()
                .filter(FieldMapping::isUniqueKey)
                .map(FieldMapping::getColumn)
                .filter(column -> document.get(column) != null)
                .forEach(column -> addFilterCandidate(candidates, seenFields, document, List.of(column)));

        return candidates;
    }

    private void addFilterCandidate(
            List<FilterCandidate> candidates, Set<List<String>> seenFields, Document document, List<String> fields) {
        List<String> normalizedFields = List.copyOf(fields);
        if (!seenFields.add(normalizedFields)) return;

        List<Bson> filters = normalizedFields.stream()
                .map(column -> Filters.eq(column, document.get(column)))
                .toList();

        Bson filter = filters.size() == 1 ? filters.getFirst() : Filters.and(filters);
        candidates.add(new FilterCandidate(filter, buildCandidateKey(document, normalizedFields), normalizedFields));
    }

    private String buildCandidateKey(Document document, List<String> fields) {
        return fields.stream()
                .map(column -> "%s=%s".formatted(column, String.valueOf(document.get(column))))
                .reduce("%s|%s"::formatted)
                .orElse("");
    }

    List<UniqueIndexDefinition> uniqueIndexDefinitionsFromDocuments(Iterable<Document> indexes) {
        List<UniqueIndexDefinition> definitions = new ArrayList<>();
        for (Document index : indexes) {
            if (!Boolean.TRUE.equals(index.getBoolean("unique"))) continue;

            Document keys = index.get("key", Document.class);
            if (keys == null || keys.isEmpty()) continue;

            List<String> fields =
                    keys.keySet().stream().filter(field -> !"_id".equals(field)).toList();

            if (!fields.isEmpty()) definitions.add(new UniqueIndexDefinition(fields));
        }
        return definitions;
    }

    private List<UniqueIndexDefinition> uniqueIndexes(MongoCollection<Document> collection) {
        return uniqueIndexDefinitionsFromDocuments(collection.listIndexes());
    }

    private Object resolveReturnedKey(Document document, Map<String, FieldMapping> types) {
        Optional<FieldMapping> autoIncrement = autoIncrementMappings(types).findFirst();
        if (autoIncrement.isPresent())
            return toLong(document.get(autoIncrement.get().getColumn()));

        Object primaryKey = findPrimaryKey(types)
                .map(FieldMapping::getColumn)
                .map(document::get)
                .orElse(null);
        return primaryKey == null ? document.get("_id") : primaryKey;
    }

    private String resolveTopology(String mongoUrl, ConnectionString connectionString) {
        if (mongoUrl.startsWith("mongodb+srv://")) return "srv";
        if (connectionString.getRequiredReplicaSetName() != null) {
            return "replicaSet=" + connectionString.getRequiredReplicaSetName();
        }
        if (connectionString.getHosts().size() > 1) return "seedList";
        return "singleHost";
    }

    private Optional<FieldMapping> findPrimaryKey(Map<String, FieldMapping> types) {
        return types.values().stream().filter(FieldMapping::isPrimaryKey).findFirst();
    }

    private Stream<FieldMapping> autoIncrementMappings(Map<String, FieldMapping> types) {
        return types.values().stream().filter(FieldMapping::isAutoIncrement);
    }

    private Object normalizeNumber(Object value) {
        return value instanceof Number number ? number.longValue() : value;
    }

    private String normalizeTypeName(String nativeType) {
        String normalized = nativeType.trim().replace("_", "").replace("-", "").toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "STRING", "TEXT" -> "String";
            case "INT", "INT32", "INTEGER" -> "Int32";
            case "LONG", "INT64", "NUMBER", "BIGINT" -> "Int64";
            case "DOUBLE" -> "Double";
            case "DECIMAL", "DECIMAL128", "NUMERIC" -> "Decimal128";
            case "BOOL", "BOOLEAN" -> "Boolean";
            case "DATE", "DATETIME", "TIMESTAMP" -> "Date";
            case "OBJECTID" -> "ObjectId";
            case "DOCUMENT", "OBJECT" -> "Document";
            case "ARRAY", "LIST" -> "Array";
            default -> throw new BadRequestException(ErrorCode.BAD_REQUEST, "Tipo MongoDB inválido: " + nativeType);
        };
    }

    private boolean isNullableBlankType(ColumnType type) {
        return type == ColumnType.NUMBER
                || type == ColumnType.INTEGER
                || type == ColumnType.DECIMAL
                || type == ColumnType.DATE
                || type == ColumnType.DATETIME;
    }

    private Boolean toBoolean(Object value) {
        if (value instanceof Boolean bool) return bool;
        String str = String.valueOf(value).trim();
        return Stream.of("true", "1", "yes", "sim").anyMatch(s -> s.equalsIgnoreCase(str));
    }

    private Integer toInteger(Object value) {
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.valueOf(String.valueOf(value).trim());
        } catch (Exception exception) {
            return null;
        }
    }

    private Long toLong(Object value) {
        if (value instanceof Number number) return number.longValue();
        try {
            return Long.valueOf(String.valueOf(value).trim());
        } catch (Exception exception) {
            return null;
        }
    }

    private Double toDouble(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        try {
            return Double.valueOf(String.valueOf(value).trim());
        } catch (Exception exception) {
            return null;
        }
    }

    private Decimal128 toDecimal(Object value) {
        if (value instanceof Decimal128 decimal) return decimal;
        if (value instanceof BigDecimal decimal) return new Decimal128(decimal);
        if (value instanceof Number number) return new Decimal128(BigDecimal.valueOf(number.doubleValue()));
        try {
            return Decimal128.parse(String.valueOf(value).trim());
        } catch (Exception exception) {
            return null;
        }
    }

    private Date toDate(Object value) {
        if (value instanceof Date date) return date;
        if (value instanceof Instant instant) return Date.from(instant);
        if (value instanceof OffsetDateTime offsetDateTime) return Date.from(offsetDateTime.toInstant());
        if (value instanceof ZonedDateTime zonedDateTime) return Date.from(zonedDateTime.toInstant());
        if (value instanceof LocalDateTime localDateTime)
            return Date.from(localDateTime.atZone(ZONE_BR).toInstant());
        if (value instanceof LocalDate localDate)
            return Date.from(localDate.atStartOfDay(ZONE_BR).toInstant());
        if (value instanceof String str) {
            try {
                return Date.from(Instant.parse(str.trim()));
            } catch (Exception ignored) {
                try {
                    return Date.from(
                            LocalDate.parse(str.trim()).atStartOfDay(ZONE_BR).toInstant());
                } catch (Exception exception) {
                    return null;
                }
            }
        }
        return null;
    }

    private ObjectId toObjectId(Object value) {
        if (value instanceof ObjectId objectId) return objectId;
        String str = String.valueOf(value).trim();
        return ObjectId.isValid(str) ? new ObjectId(str) : null;
    }

    private Object toDocumentValue(Object value) {
        if (value instanceof Document document) return document;
        if (value instanceof Map<?, ?> map) {
            Document document = new Document();
            map.forEach((key, mapValue) -> document.put(String.valueOf(key), mapValue));
            return document;
        }
        return value;
    }

    private Object toArrayValue(Object value) {
        if (value instanceof Collection<?> collection) return new ArrayList<>(collection);
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            List<Object> values = new ArrayList<>(length);
            IntStream.range(0, length).forEach(index -> values.add(java.lang.reflect.Array.get(value, index)));
            return values;
        }
        return List.of(value);
    }

    private void validateCollectionName(String name) {
        if (name == null
                || name.isBlank()
                || name.contains("$")
                || name.contains("\0")
                || name.startsWith("system.")
                || !name.matches("[a-zA-Z0-9_.-]+")) {
            throw new BadRequestException(ErrorCode.BAD_REQUEST, "Collection MongoDB inválida");
        }
    }

    private void validateFieldNames(Map<String, FieldMapping> types) {
        types.values().stream().map(FieldMapping::getColumn).forEach(column -> {
            if (column == null
                    || column.isBlank()
                    || column.contains("$")
                    || column.contains(".")
                    || column.contains("\0")) {
                throw new BadRequestException(ErrorCode.BAD_REQUEST, "Campo MongoDB inválido: " + column);
            }
        });
    }

    private String indexName(String prefix, String collectionName, String column) {
        return "%s_%s_%s".formatted(prefix, collectionName, column).replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private <T> Stream<List<T>> partition(List<T> list, int size) {
        if (list.isEmpty()) return Stream.empty();
        return IntStream.iterate(0, index -> index < list.size(), index -> index + size)
                .mapToObj(index -> list.subList(index, Math.min(index + size, list.size())));
    }

    @FunctionalInterface
    interface DatabaseFunction<T> {
        T apply(MongoDatabase database);
    }

    record UniqueIndexDefinition(List<String> fields) {}

    private record FilterCandidate(Bson filter, String key, List<String> fields) {}
}
