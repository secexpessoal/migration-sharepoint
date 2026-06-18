/*
 * SPDX-License-Identifier: BSD-3-Clause
 *
 * Copyright (c) 2026 Vinícius Gabriel Pereira Leitão
 * Licensed under the BSD 3-Clause License.
 * See LICENSE file in the project root for full license information.
 */
package org.migration.sharepoint.infra.writer;

import java.util.List;
import java.util.Map;
import org.migration.sharepoint.data.enums.ColumnType;
import org.migration.sharepoint.data.enums.TargetDb;
import org.migration.sharepoint.data.model.FieldMapping;
import org.migration.sharepoint.data.model.ForeignKeyDefinition;

public interface MigrationWriter {

    boolean supports(TargetDb targetDb);

    /**
     * Executa full replace: apaga todo o conteúdo do destino e insere {@code rows}.
     *
     * @param connectionKey
     *            chave registrada no ConnectionRegistry
     * @param targetName
     *            nome da tabela (SQL) ou collection (MongoDB)
     * @param rows
     *            linhas já mapeadas — chave = nome da coluna de destino
     * @param columnTypes
     *            mapa de coluna de destino → FieldMapping com tipo declarado
     * @param foreignKeys
     *            lista de chaves estrangeiras para este nodo (apenas para SQL)
     * @return lista de IDs gerados ou resolvidos para cada linha inserida/atualizada, na mesma ordem de {@code rows}
     */
    List<Object> write(
            String connectionKey,
            String targetName,
            List<Map<String, Object>> rows,
            Map<String, FieldMapping> columnTypes,
            List<ForeignKeyDefinition> foreignKeys,
            String parentTableName);

    /**
     * Definições de tipos nativos deste adapter com especificação de parâmetros
     * aceitos.
     */
    List<NativeTypeDefinition> typeDefinitions();

    /** Mapeamento de tipos canônicos para os tipos nativos deste adapter. */
    Map<ColumnType, String> canonicalMapping();
}
