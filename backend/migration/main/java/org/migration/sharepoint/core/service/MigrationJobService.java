/*
 * SPDX-License-Identifier: BSD-3-Clause
 *
 * Copyright (c) 2026 Vinícius Gabriel Pereira Leitão
 * Licensed under the BSD 3-Clause License.
 * See LICENSE file in the project root for full license information.
 */
package org.migration.sharepoint.core.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.migration.sharepoint.controller.job.dto.JobRequest;
import org.migration.sharepoint.controller.job.dto.JobResponse;
import org.migration.sharepoint.controller.job.dto.LogResponse;
import org.migration.sharepoint.data.enums.ColumnType;
import org.migration.sharepoint.data.model.CustomFieldDefinition;
import org.migration.sharepoint.data.model.FieldMapping;
import org.migration.sharepoint.data.model.ForeignKeyDefinition;
import org.migration.sharepoint.data.model.JobNode;
import org.migration.sharepoint.data.model.MigrationJob;
import org.migration.sharepoint.data.repository.MigrationJobRepository;
import org.migration.sharepoint.data.repository.MigrationLogRepository;
import org.migration.sharepoint.infra.connection.ConnectionRegistry;
import org.migration.sharepoint.infra.exception.ErrorCode;
import org.migration.sharepoint.infra.exception.custom.BadRequestException;
import org.migration.sharepoint.infra.exception.custom.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MigrationJobService {

    private final MigrationJobRepository jobRepository;
    private final MigrationLogRepository logRepository;
    private final QuartzSchedulerService quartzSchedulerService;
    private final ConnectionRegistry connectionRegistry;

    public List<JobResponse> findAll() {
        return jobRepository.findAll().stream().map(this::toResponse).toList();
    }

    public JobResponse findById(Long id) {
        return toResponse(findOrThrow(id));
    }

    @Transactional
    public JobResponse create(JobRequest request) {
        validateScheduleFields(request);
        validateNode(request.migration(), "migration", null);
        connectionRegistry.resolveUrl(request.connectionKey());
        MigrationJob job = jobRepository.save(fromRequest(request));
        quartzSchedulerService.schedule(job);
        return toResponse(job);
    }

    @Transactional
    public JobResponse update(Long id, JobRequest request) {
        validateScheduleFields(request);
        validateNode(request.migration(), "migration", null);
        connectionRegistry.resolveUrl(request.connectionKey());

        MigrationJob job = findOrThrow(id);
        applyRequest(job, request);
        job = jobRepository.save(job);

        quartzSchedulerService.reschedule(job);
        return toResponse(job);
    }

    @Transactional
    public void delete(Long id) {
        MigrationJob job = findOrThrow(id);
        quartzSchedulerService.unschedule(id);
        jobRepository.delete(job);
    }

    public void runNow(Long id) {
        findOrThrow(id);
        quartzSchedulerService.triggerNow(id);
    }

    public List<LogResponse> findLogs(Long id) {
        findOrThrow(id);
        return logRepository.findByJobIdOrderByStartedAtDesc(id).stream()
                .map(log -> new LogResponse(
                        log.getId(),
                        log.getJob().getId(),
                        log.getStatus(),
                        log.getStartedAt(),
                        log.getFinishedAt(),
                        log.getErrorMessage()))
                .toList();
    }

    public Page<LogResponse> findLogs(Long id, Pageable pageable) {
        findOrThrow(id);
        return logRepository.findByJobIdOrderByStartedAtDesc(id, pageable)
                .map(log -> new LogResponse(
                        log.getId(),
                        log.getJob().getId(),
                        log.getStatus(),
                        log.getStartedAt(),
                        log.getFinishedAt(),
                        log.getErrorMessage()));
    }

    private void validateScheduleFields(JobRequest request) {
        switch (request.scheduleType()) {
            case INTERVAL -> {
                if (request.intervalValue() == null) {
                    throw new BadRequestException(ErrorCode.BAD_REQUEST, "scheduleType INTERVAL requer intervalValue");
                }
                if (request.intervalUnit() == null) {
                    throw new BadRequestException(ErrorCode.BAD_REQUEST, "scheduleType INTERVAL requer intervalUnit");
                }
            }
            case CRON -> {
                if (request.cronExpression() == null || request.cronExpression().isBlank()) {
                    throw new BadRequestException(ErrorCode.BAD_REQUEST, "scheduleType CRON requer cronExpression");
                }
            }
            case MANUAL, CONTINUOUS -> {}
        }
    }

    private void validateNode(JobNode node, String path, JobNode parentNode) {
        validateRequiredNodeMetadata(node, path);

        List<String> availableNodeColumns = extractAvailableColumns(node);

        validateForeignKeyIntegrity(node, path, parentNode, availableNodeColumns);
        validateChildrenRecursively(node, path, availableNodeColumns);
    }

    private void validateRequiredNodeMetadata(JobNode node, String path) {
        if (isStringEmpty(node.getSiteId())) throwBadRequest("%s.siteId não pode ser vazio".formatted(path));

        if (isStringEmpty(node.getListId())) throwBadRequest("%s.listId não pode ser vazio".formatted(path));

        if (isStringEmpty(node.getTableName())) throwBadRequest("%s.tableName não pode ser vazio".formatted(path));

        if (node.getFieldMappings() == null || node.getFieldMappings().isEmpty()) {
            throwBadRequest("%s.fieldMappings não pode ser vazio".formatted(path));
        }
    }

    private List<String> extractAvailableColumns(JobNode node) {
        Stream<String> mappedColumns = node.getFieldMappings().values().stream().map(FieldMapping::getColumn);

        Stream<String> customColumns = Optional.ofNullable(node.getCustomFields()).map(Map::values).stream()
                .flatMap(Collection::stream)
                .map(CustomFieldDefinition::getColumn);

        return Stream.concat(mappedColumns, customColumns)
                .filter(Objects::nonNull)
                .filter(column -> !column.isBlank())
                .distinct()
                .toList();
    }

    private void validateForeignKeyIntegrity(
            JobNode node, String path, JobNode parentNode, List<String> availableNodeColumns) {
        List<ForeignKeyDefinition> foreignKeys =
                Optional.ofNullable(node.getForeignKeys()).orElse(List.of());
        if (foreignKeys.isEmpty()) return;

        if (parentNode == null) {
            throwBadRequest("%s não pode ter chaves estrangeiras pois é o nodo raiz".formatted(path));
        }

        List<String> availableParentColumns = extractAvailableColumns(parentNode);

        IntStream.range(0, foreignKeys.size()).forEach(index -> {
            ForeignKeyDefinition foreignKey = foreignKeys.get(index);
            String foreignKeyPath = "%s.foreignKeys[%d]".formatted(path, index);

            if (!availableNodeColumns.contains(foreignKey.getLocalColumn())) {
                throwBadRequest("%s.localColumn '%s' não existe no nodo"
                        .formatted(foreignKeyPath, foreignKey.getLocalColumn()));
            }

            if (!availableParentColumns.contains(foreignKey.getParentColumn())) {
                throwBadRequest("%s.parentColumn '%s' não existe no nodo pai"
                        .formatted(foreignKeyPath, foreignKey.getParentColumn()));
            }

            // Validação de Unicidade no Pai
            FieldMapping parentMapping = findFieldMappingByColumn(parentNode, foreignKey.getParentColumn())
                    .orElse(null);

            if (parentMapping == null) {
                throwBadRequest("%s.parentColumn '%s' deve ser um campo mapeado e possuir Primary Key ou Unique Key"
                        .formatted(foreignKeyPath, foreignKey.getParentColumn()));
            }

            if (!parentMapping.isPrimaryKey() && !parentMapping.isUniqueKey()) {
                throwBadRequest("%s.parentColumn '%s' não é Primary Key nem Unique Key no nodo pai"
                        .formatted(foreignKeyPath, foreignKey.getParentColumn()));
            }

            // Validação de Tipos
            ColumnType localType = getColumnType(node, foreignKey.getLocalColumn());
            ColumnType parentType = getColumnType(parentNode, foreignKey.getParentColumn());

            if (localType != parentType) {
                throwBadRequest("%s.localColumn '%s' (tipo %s) incompatível com parentColumn '%s' (tipo %s)"
                        .formatted(
                                foreignKeyPath,
                                foreignKey.getLocalColumn(),
                                localType,
                                foreignKey.getParentColumn(),
                                parentType));
            }
        });
    }

    private Optional<FieldMapping> findFieldMappingByColumn(JobNode node, String column) {
        Optional<FieldMapping> mapping = node.getFieldMappings().values().stream()
                .filter(fm -> column.equals(fm.getColumn()))
                .findFirst();

        if (mapping.isPresent()) {
            return mapping;
        }

        return Optional.ofNullable(node.getCustomFields()).map(Map::values).stream()
                .flatMap(Collection::stream)
                .filter(cf -> column.equals(cf.getColumn()))
                .map(cf -> new FieldMapping(
                        cf.getColumn(), cf.getType(), cf.getNativeType(), cf.isPrimaryKey(), cf.isUniqueKey()))
                .findFirst();
    }

    private ColumnType getColumnType(JobNode node, String column) {
        return findFieldMappingByColumn(node, column).map(FieldMapping::getType).orElse(ColumnType.TEXT);
    }

    private void validateChildrenRecursively(JobNode node, String path, List<String> availableNodeColumns) {
        List<JobNode> children = Optional.ofNullable(node.getChildren()).orElse(List.of());
        IntStream.range(0, children.size()).forEach(index -> {
            String childPath = "%s.children[%d]".formatted(path, index);
            validateNode(children.get(index), childPath, node);
        });
    }

    private boolean isStringEmpty(String s) {
        return s == null || s.isBlank();
    }

    private void throwBadRequest(String message) {
        throw new BadRequestException(ErrorCode.BAD_REQUEST, message);
    }

    private MigrationJob findOrThrow(Long id) {
        return jobRepository
                .findById(id)
                .orElseThrow(
                        () -> new NotFoundException(ErrorCode.JOB_NOT_FOUND, "Job id=%d não encontrado".formatted(id)));
    }

    private MigrationJob fromRequest(JobRequest request) {
        return MigrationJob.builder()
                .name(request.name())
                .pageSize(request.pageSize())
                .targetDb(request.targetDb())
                .connectionKey(request.connectionKey())
                .scheduleType(request.scheduleType())
                .intervalValue(request.intervalValue())
                .intervalUnit(request.intervalUnit())
                .cronExpression(request.cronExpression())
                .migration(request.migration())
                .build();
    }

    private void applyRequest(MigrationJob job, JobRequest request) {
        job.setName(request.name());
        job.setPageSize(request.pageSize());
        job.setTargetDb(request.targetDb());
        job.setConnectionKey(request.connectionKey());
        job.setScheduleType(request.scheduleType());
        job.setIntervalValue(request.intervalValue());
        job.setIntervalUnit(request.intervalUnit());
        job.setCronExpression(request.cronExpression());
        job.setMigration(request.migration());
    }

    private JobResponse toResponse(MigrationJob job) {
        return new JobResponse(
                job.getId(),
                job.getName(),
                job.getPageSize(),
                job.getTargetDb(),
                job.getConnectionKey(),
                job.getScheduleType(),
                job.getIntervalValue(),
                job.getIntervalUnit(),
                job.getCronExpression(),
                job.getCreatedAt(),
                job.getUpdatedAt(),
                job.getMigration());
    }
}
