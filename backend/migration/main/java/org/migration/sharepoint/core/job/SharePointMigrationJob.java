/*
 * SPDX-License-Identifier: BSD-3-Clause
 *
 * Copyright (c) 2026 Vinícius Gabriel Pereira Leitão
 * Licensed under the BSD 3-Clause License.
 * See LICENSE file in the project root for full license information.
 */
package org.migration.sharepoint.core.job;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.migration.sharepoint.data.enums.*;
import org.migration.sharepoint.data.model.*;
import org.migration.sharepoint.data.repository.MigrationJobRepository;
import org.migration.sharepoint.data.repository.MigrationLogRepository;
import org.migration.sharepoint.infra.exception.ErrorCode;
import org.migration.sharepoint.infra.exception.base.AppException;
import org.migration.sharepoint.infra.exception.custom.BadRequestException;
import org.migration.sharepoint.infra.exception.custom.InfrastructureException;
import org.migration.sharepoint.infra.graph.GraphClient;
import org.migration.sharepoint.infra.writer.MigrationWriter;
import org.migration.sharepoint.infra.writer.MigrationWriterRegistry;
import org.quartz.*;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;

@Slf4j
@DisallowConcurrentExecution
public class SharePointMigrationJob implements Job {

    private static final ZoneId ZONE_BR = ZoneId.of("America/Sao_Paulo");

    @Autowired
    private MigrationJobRepository jobRepository;

    @Autowired
    private MigrationLogRepository logRepository;

    @Autowired
    private GraphClient graphClient;

    @Autowired
    private MigrationWriterRegistry writerRegistry;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        Long jobId = context.getJobDetail().getJobDataMap().getLong("jobId");
        MDC.put("jobId", String.valueOf(jobId));
        MDC.put("requestId", "system");
        MDC.put("clientIp", "internal");

        try {
            executeInternal(jobId, context);
        } finally {
            MDC.remove("jobId");
            MDC.remove("requestId");
            MDC.remove("clientIp");
        }
    }

    private void executeInternal(Long jobId, JobExecutionContext context) throws JobExecutionException {
        log.info("Iniciando execução do job id={}", jobId);

        MigrationJob job = jobRepository
                .findById(jobId)
                .orElseThrow(() -> new JobExecutionException("Job %d não encontrado".formatted(jobId)));

        MigrationLog migrationLog = logRepository.save(MigrationLog.builder()
                .job(job)
                .status(JobStatus.RUNNING)
                .startedAt(LocalDateTime.now())
                .build());

        try {
            JobNode rootNode = job.getMigration();
            if (rootNode == null) {
                log.warn("Job id={} não possui configuração de migração", jobId);
                finalizeSuccess(migrationLog, 0);
                return;
            }

            RelationalContext relationalContext = new RelationalContext();
            int totalMigrated = syncNode(
                    rootNode,
                    job.getConnectionKey(),
                    job.getTargetDb(),
                    job.getPageSize(),
                    jobId,
                    null,
                    relationalContext);

            finalizeSuccess(migrationLog, totalMigrated);

            if (job.getScheduleType() == ScheduleType.CONTINUOUS) {
                triggerNextRun(context);
            }

        } catch (AppException exception) {
            handleFailure(
                    migrationLog,
                    exception.getMessage(),
                    exception.getErrorCode().name());
        } catch (Exception exception) {
            handleFailure(
                    migrationLog,
                    Optional.ofNullable(exception.getMessage())
                            .orElse(exception.getClass().getSimpleName()),
                    "UNEXPECTED_ERROR");
            throw new JobExecutionException(exception);
        }
    }

    private void finalizeSuccess(MigrationLog migrationLog, int total) {
        migrationLog.setStatus(JobStatus.SUCCESS);
        migrationLog.setFinishedAt(LocalDateTime.now());
        logRepository.save(migrationLog);
        Long jobId = Optional.ofNullable(migrationLog.getJob())
                .map(MigrationJob::getId)
                .orElse(0L);
        log.info("Job id={} concluído — {} registros migrados", jobId, total);
    }

    private void triggerNextRun(JobExecutionContext context) {
        try {
            context.getScheduler().triggerJob(context.getJobDetail().getKey());
        } catch (SchedulerException exception) {
            log.error("Erro ao disparar próxima execução CONTINUOUS", exception);
        }
    }

    private void handleFailure(MigrationLog migrationLog, String message, String code) {
        log.warn("Job falhou: [{}] {}", code, message);
        migrationLog.setStatus(JobStatus.FAILED);
        migrationLog.setFinishedAt(LocalDateTime.now());
        migrationLog.setErrorMessage(message);
        logRepository.save(migrationLog);
    }

    private Object generateCustomValue(CustomFieldDefinition definition) {
        return switch (definition.getFunction()) {
            case CURRENT_TIMESTAMP_UTC_3 -> OffsetDateTime.now(ZONE_BR).toLocalDateTime();
            case CURRENT_DATE_BR -> OffsetDateTime.now(ZONE_BR).toLocalDate();
            case UUID_GEN -> UUID.randomUUID().toString();
            case STATIC_VALUE -> definition.getStaticValue();
            case AUTO_INCREMENT -> null;
        };
    }

    private static class RelationalContext {
        private final Map<String, Map<Object, Object>> idMap = new java.util.concurrent.ConcurrentHashMap<>();

        public void addMapping(String table, Object sharePointId, Object databaseId) {
            Optional.ofNullable(sharePointId)
                    .ifPresent(id -> idMap.computeIfAbsent(table, k -> new java.util.concurrent.ConcurrentHashMap<>())
                            .put(id, databaseId));
        }

        public Object getDbId(String table, Object sharePointId) {
            return Optional.ofNullable(sharePointId)
                    .map(id -> {
                        Map<Object, Object> tableMap = idMap.getOrDefault(table, Collections.emptyMap());
                        return Optional.ofNullable(tableMap.get(id))
                                .or(() -> Optional.ofNullable(tableMap.get(String.valueOf(id))))
                                .or(() -> tryParseInt(id).map(tableMap::get))
                                .orElse(null);
                    })
                    .orElse(null);
        }

        private Optional<Integer> tryParseInt(Object value) {
            if (!(value instanceof String str)) return Optional.empty();
            try {
                return Optional.of(Integer.valueOf(str));
            } catch (Exception exception) {
                return Optional.empty();
            }
        }
    }

    private int syncNode(
            JobNode node,
            String connectionKey,
            TargetDb targetDb,
            int pageSize,
            Long jobId,
            String parent,
            RelationalContext context) {
        if (node == null) return 0;

        Set<String> fields = Stream.concat(node.getFieldMappings().keySet().stream(), Stream.of("id"))
                .collect(Collectors.toSet());
        List<Map<String, Object>> rawData =
                graphClient.fetchListItems(node.getSiteId(), node.getListId(), fields, pageSize);

        List<Map<String, Object>> mappedData = applyFieldMapping(rawData, node.getFieldMappings());

        injectCustomFields(
                mappedData, Optional.ofNullable(node.getCustomFields()).orElse(Map.of()));
        resolveForeignKeys(node, mappedData, context, parent);

        List<String> uniqueCols = node.getFieldMappings().values().stream()
                .filter(FieldMapping::isUniqueKey)
                .map(FieldMapping::getColumn)
                .toList();

        List<Map<String, Object>> dataToWrite;

        if (!uniqueCols.isEmpty()) {
            dataToWrite = mappedData.stream().filter(distinctByKeys(uniqueCols)).collect(Collectors.toList());
        } else {
            dataToWrite = mappedData;
        }

        MigrationWriter writer = writerRegistry.get(targetDb);
        if (writer == null) {
            throw new InfrastructureException(
                    ErrorCode.INTERNAL_SERVER_ERROR, "Writer não encontrado para o banco %s".formatted(targetDb));
        }

        List<Object> generatedKeys = writer.write(
                connectionKey,
                node.getTableName(),
                dataToWrite,
                buildCombinedTypes(node),
                node.getForeignKeys(),
                parent);

        populateRelationalContext(node.getTableName(), mappedData, dataToWrite, generatedKeys, uniqueCols, context);

        log.info(
                "Job id={} Nodo={}: {} registros processados ({} inseridos no banco)",
                jobId,
                node.getTableName(),
                mappedData.size(),
                dataToWrite.size());

        int selfCount = dataToWrite.size();
        int childrenCount = syncTree(
                Optional.ofNullable(node.getChildren()).orElse(List.of()),
                connectionKey,
                targetDb,
                pageSize,
                jobId,
                node.getTableName(),
                context);

        return selfCount + childrenCount;
    }

    private java.util.function.Predicate<Map<String, Object>> distinctByKeys(List<String> keys) {
        Set<List<Object>> seen = new java.util.HashSet<>();
        return row -> {
            List<Object> values = keys.stream()
                    .map(row::get)
                    .map(val -> val instanceof String s ? s.trim().toUpperCase() : val)
                    .toList();
            return seen.add(values);
        };
    }

    private void resolveForeignKeys(
            JobNode node, List<Map<String, Object>> data, RelationalContext context, String parent) {
        if (parent == null || node.getForeignKeys() == null) return;
        node.getForeignKeys()
                .forEach(foreignKey -> data.forEach(row -> {
                    // Prioridade 1: Use o valor já presente na coluna (se for um ID de origem de outra lista)
                    Object lookupValue = row.get(foreignKey.getLocalColumn());

                    // Prioridade 2: AUTONORMALIZAÇÃO. Se a coluna estiver vazia (por exemplo, STATIC_VALUE: ""),
                    // significa que estamos extraindo o pai do MESMO item da lista.

                    // Usamos o próprio ID do SharePoint do item para encontrar o ID do registro pai
                    // criado para este mesmo item da lista.
                    if (lookupValue == null || (lookupValue instanceof String s && s.isBlank())) {
                        lookupValue = row.get("_sp_id");
                    }

                    Object databaseId = context.getDbId(parent, lookupValue);
                    if (databaseId != null) {
                        row.put(foreignKey.getLocalColumn(), databaseId);
                    }
                }));
    }

    private void populateRelationalContext(
            String table,
            List<Map<String, Object>> allMappedData,
            List<Map<String, Object>> writtenData,
            List<Object> generatedKeys,
            List<String> uniqueCols,
            RelationalContext context) {

        Map<List<Object>, Object> valueToDbId = new HashMap<>();

        if (!uniqueCols.isEmpty() && writtenData.size() == generatedKeys.size()) {
            for (int it = 0; it < writtenData.size(); it++) {
                List<Object> values = uniqueCols.stream()
                        .map(writtenData.get(it)::get)
                        .map(val -> val instanceof String s ? s.trim().toUpperCase() : val)
                        .toList();
                valueToDbId.put(values, generatedKeys.get(it));
            }
        }

        // 2. Map every SharePoint item to a Database ID
        for (int it = 0; it < allMappedData.size(); it++) {
            Map<String, Object> row = allMappedData.get(it);
            Object spId = row.get("_sp_id");

            Object dbId;
            if (uniqueCols.isEmpty()) {
                // Standard 1:1 mapping (indices correspond if no distinct filter used)
                dbId = (it < generatedKeys.size()) ? generatedKeys.get(it) : null;
            } else {
                // N:1 mapping (Self-Normalization)
                List<Object> values = uniqueCols.stream()
                        .map(row::get)
                        .map(val -> val instanceof String s ? s.trim().toUpperCase() : val)
                        .toList();

                dbId = valueToDbId.get(values);
            }

            if (dbId != null) {
                context.addMapping(table, spId, dbId);
            }
        }
    }

    private Map<String, FieldMapping> buildCombinedTypes(JobNode node) {
        Map<String, FieldMapping> combinedMappings = new HashMap<>();
        node.getFieldMappings().values().forEach(mapping -> combinedMappings.put(mapping.getColumn(), mapping));
        Optional.ofNullable(node.getCustomFields()).ifPresent(customFields -> customFields
                .values()
                .forEach(customField -> combinedMappings.put(
                        customField.getColumn(),
                        new FieldMapping(
                                customField.getColumn(),
                                customField.getFunction() == CustomFunction.AUTO_INCREMENT
                                        ? ColumnType.INTEGER
                                        : customField.getType(),
                                customField.getNativeType(),
                                customField.isPrimaryKey(),
                                customField.isUniqueKey(),
                                customField.getFunction() == CustomFunction.AUTO_INCREMENT))));
        return combinedMappings;
    }

    private void injectCustomFields(List<Map<String, Object>> rows, Map<String, CustomFieldDefinition> customFields) {
        rows.forEach(
                row -> customFields.values().forEach(field -> row.put(field.getColumn(), generateCustomValue(field))));
    }

    private int syncTree(
            List<JobNode> nodes,
            String connectionKey,
            TargetDb targetDb,
            int pageSize,
            Long jobId,
            String parent,
            RelationalContext context) {
        if (nodes.isEmpty()) return 0;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            return executor
                    .invokeAll(nodes.stream()
                            .filter(Objects::nonNull)
                            .map(node -> (java.util.concurrent.Callable<Integer>)
                                    () -> syncNode(node, connectionKey, targetDb, pageSize, jobId, parent, context))
                            .toList())
                    .stream()
                    .mapToInt(future -> {
                        try {
                            return future.get();
                        } catch (Exception exception) {
                            throw new RuntimeException(exception);
                        }
                    })
                    .sum();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(exception);
        }
    }

    private List<Map<String, Object>> applyFieldMapping(
            List<Map<String, Object>> rows, Map<String, FieldMapping> mappings) {
        Map<String, FieldMapping> normalizedMappings = mappings.entrySet().stream()
                .collect(Collectors.toMap(entry -> entry.getKey().toLowerCase(), Map.Entry::getValue));

        List<Map<String, Object>> mappedRows = rows.stream()
                .map(row -> {
                    Map<String, Object> mappedRow = new LinkedHashMap<>();
                    // CRITICAL: Preserve the original SharePoint ID inside the mapped row
                    mappedRow.put("_sp_id", row.get("id"));

                    row.forEach((key, value) -> {
                        String normalizedKey = key.toLowerCase();
                        if (normalizedMappings.containsKey(normalizedKey)) {
                            mappedRow.put(normalizedMappings.get(normalizedKey).getColumn(), value);
                        }
                    });
                    return mappedRow;
                })
                .collect(Collectors.toList());

        if (!rows.isEmpty()) {
            boolean hasAnyMappedField = mappedRows.stream().anyMatch(row -> row.size() > 1);
            if (!hasAnyMappedField) {
                throw new BadRequestException(
                        ErrorCode.MIGRATION_EMPTY_MAPPING, "Nenhum campo mapeado encontrado nos dados do SharePoint.");
            }
        }
        return mappedRows;
    }
}
