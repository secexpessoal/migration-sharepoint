/*
 * SPDX-License-Identifier: BSD-3-Clause
 *
 * Copyright (c) 2026 Vinícius Gabriel Pereira Leitão
 * Licensed under the BSD 3-Clause License.
 * See LICENSE file in the project root for full license information.
 */
package org.migration.sharepoint.controller.job;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.migration.sharepoint.controller.job.dto.JobRequest;
import org.migration.sharepoint.controller.job.dto.JobResponse;
import org.migration.sharepoint.controller.job.dto.LogResponse;
import org.migration.sharepoint.core.service.MigrationJobService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Jobs")
@RestController
@RequestMapping("/v1/jobs")
@RequiredArgsConstructor
public class MigrationJobController {

    private final MigrationJobService service;

    @Operation(summary = "Listar todos os jobs")
    @GetMapping
    public List<JobResponse> findAll() {
        return service.findAll();
    }

    @Operation(summary = "Buscar job por ID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Job encontrado"),
        @ApiResponse(responseCode = "404", description = "Job não encontrado")
    })
    @GetMapping("/{id}")
    public JobResponse findById(@Parameter(description = "ID do job") @PathVariable Long id) {
        return service.findById(id);
    }

    @Operation(
            summary = "Criar novo job",
            description =
                    "Cria e agenda um novo job de migração. O campo `connectionKey` deve referenciar uma conexão previamente registrada em `POST /v1/connections`.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Job criado com sucesso"),
        @ApiResponse(responseCode = "400", description = "Payload inválido"),
        @ApiResponse(responseCode = "404", description = "connectionKey não encontrada no registry")
    })
    @PostMapping
    public ResponseEntity<JobResponse> create(@RequestBody @Valid JobRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @Operation(summary = "Atualizar job", description = "Substitui todas as configurações do job e reagenda no Quartz.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Job atualizado"),
        @ApiResponse(responseCode = "400", description = "Payload inválido"),
        @ApiResponse(responseCode = "404", description = "Job ou connectionKey não encontrados")
    })
    @PutMapping("/{id}")
    public JobResponse update(
            @Parameter(description = "ID do job") @PathVariable Long id, @RequestBody @Valid JobRequest request) {
        return service.update(id, request);
    }

    @Operation(summary = "Excluir job", description = "Remove o job do banco e cancela o agendamento no Quartz.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Job excluído"),
        @ApiResponse(responseCode = "404", description = "Job não encontrado")
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@Parameter(description = "ID do job") @PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "Disparar execução manual",
            description =
                    "Executa o job imediatamente, independente do agendamento configurado. Retorna 202 assim  que o disparo é enfileirado — a execução ocorre de forma assíncrona.")
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "Execução enfileirada"),
        @ApiResponse(responseCode = "404", description = "Job não encontrado")
    })
    @PostMapping("/{id}/run")
    public ResponseEntity<Void> run(@Parameter(description = "ID do job") @PathVariable Long id) {
        service.runNow(id);
        return ResponseEntity.accepted().build();
    }

    @Operation(
            summary = "Histórico de execuções",
            description = "Retorna todos os logs de execução do job em ordem decrescente de data.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Lista de logs"),
        @ApiResponse(responseCode = "404", description = "Job não encontrado")
    })
    @GetMapping("/{id}/logs")
    public List<LogResponse> logs(@Parameter(description = "ID do job") @PathVariable Long id) {
        return service.findLogs(id);
    }

    @Operation(
            summary = "Histórico de execuções paginado",
            description = "Retorna logs de execução do job em ordem decrescente de data com paginação.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Página de logs"),
        @ApiResponse(responseCode = "404", description = "Job não encontrado")
    })
    @GetMapping("/{id}/logs/paged")
    public Page<LogResponse> logsPaged(
            @Parameter(description = "ID do job") @PathVariable Long id,
            @PageableDefault(size = 20) Pageable pageable) {
        return service.findLogs(id, pageable);
    }
}
