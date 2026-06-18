/*
 * SPDX-License-Identifier: BSD-3-Clause
 *
 * Copyright (c) 2026 Vinícius Gabriel Pereira Leitão
 * Licensed under the BSD 3-Clause License.
 * See LICENSE file in the project root for full license information.
 */
package org.migration.sharepoint.data.repository;

import java.util.List;
import org.migration.sharepoint.data.model.MigrationLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MigrationLogRepository extends JpaRepository<MigrationLog, Long> {
    List<MigrationLog> findByJobIdOrderByStartedAtDesc(Long jobId);

    Page<MigrationLog> findByJobIdOrderByStartedAtDesc(Long jobId, Pageable pageable);
}
