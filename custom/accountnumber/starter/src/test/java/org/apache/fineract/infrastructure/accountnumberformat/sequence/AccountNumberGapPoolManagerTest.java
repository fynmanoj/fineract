/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.infrastructure.accountnumberformat.sequence;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

@ExtendWith(MockitoExtension.class)
class AccountNumberGapPoolManagerTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private AccountNumberGapPoolManager gapPoolManager;

    @BeforeEach
    void setUp() {
        final PlatformTransactionManager transactionManager = new AbstractPlatformTransactionManager() {

            @Override
            protected Object doGetTransaction() {
                return new Object();
            }

            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) {
                // no-op
            }

            @Override
            protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) {
                // no-op
            }

            @Override
            protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) {
                // no-op
            }
        };
        this.gapPoolManager = new AccountNumberGapPoolManager(this.jdbcTemplate, transactionManager, Runnable::run);
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default", "Asia/Kolkata", null));
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    void refillGapPoolInternal_usesConfiguredScanWindowSizeAndBatchSizeForLimit() {
        when(this.jdbcTemplate.queryForObject(eq("SELECT enabled FROM c_configuration WHERE name = ?"), eq(Boolean.class),
                eq("account-number-reuse-gaps"))).thenReturn(true);
        when(this.jdbcTemplate.queryForObject(eq("SELECT COUNT(*) FROM m_account_number_sequence WHERE scope_key LIKE ?"), eq(Integer.class),
                eq("CLIENT:GLOBAL:GAP:%"))).thenReturn(0, 10);
        when(this.jdbcTemplate.queryForObject(startsWith("SELECT COALESCE(MAX("), eq(Long.class))).thenReturn(5000L);
        when(this.jdbcTemplate.queryForObject(eq("SELECT value FROM c_configuration WHERE name = ?"), eq(Integer.class),
                eq("account-number-gap-pool-low-watermark"))).thenReturn(10);
        when(this.jdbcTemplate.queryForObject(eq("SELECT value FROM c_configuration WHERE name = ?"), eq(Integer.class),
                eq("account-number-gap-pool-batch-size"))).thenReturn(100);
        when(this.jdbcTemplate.queryForObject(eq("SELECT value FROM c_configuration WHERE name = ?"), eq(Integer.class),
                eq("startup-gap-scan-fill-size"))).thenReturn(500);
        when(this.jdbcTemplate.queryForObject(eq("SELECT value FROM c_configuration WHERE name = ?"), eq(Integer.class),
                eq("account-number-gap-refill-max-windows"))).thenReturn(50);
        when(this.jdbcTemplate.queryForObject(eq("SELECT next_value FROM m_account_number_sequence WHERE scope_key = ?"), eq(Long.class),
                eq("CLIENT:GLOBAL:GAP_SCAN"))).thenReturn(1L);
        when(this.jdbcTemplate.update(startsWith("INSERT IGNORE INTO m_account_number_sequence"), eq(1L), eq(1L), eq(500L), eq(1L), eq(500L),
                eq(500L), eq(100))).thenReturn(5);
        when(this.jdbcTemplate.update(startsWith("INSERT INTO m_account_number_sequence (scope_key, next_value) VALUES (?, ?)"),
                eq("CLIENT:GLOBAL:GAP_SCAN"), eq(501L), eq(501L))).thenReturn(1);

        this.gapPoolManager.refillGapPoolInternal();

        verify(this.jdbcTemplate).update(startsWith("INSERT IGNORE INTO m_account_number_sequence"), eq(1L), eq(1L), eq(500L), eq(1L), eq(500L),
                eq(500L), eq(100));
    }

    @Test
    void refillGapPoolInternal_usesCursorAsCandidateOffsetAtChunkBoundaries() {
        when(this.jdbcTemplate.queryForObject(eq("SELECT enabled FROM c_configuration WHERE name = ?"), eq(Boolean.class),
                eq("account-number-reuse-gaps"))).thenReturn(true);
        when(this.jdbcTemplate.queryForObject(eq("SELECT COUNT(*) FROM m_account_number_sequence WHERE scope_key LIKE ?"), eq(Integer.class),
                eq("CLIENT:GLOBAL:GAP:%"))).thenReturn(0, 1);
        when(this.jdbcTemplate.queryForObject(startsWith("SELECT COALESCE(MAX("), eq(Long.class))).thenReturn(20_000L);
        when(this.jdbcTemplate.queryForObject(eq("SELECT value FROM c_configuration WHERE name = ?"), eq(Integer.class),
                eq("account-number-gap-pool-low-watermark"))).thenReturn(1);
        when(this.jdbcTemplate.queryForObject(eq("SELECT value FROM c_configuration WHERE name = ?"), eq(Integer.class),
                eq("account-number-gap-pool-batch-size"))).thenReturn(100);
        when(this.jdbcTemplate.queryForObject(eq("SELECT value FROM c_configuration WHERE name = ?"), eq(Integer.class),
                eq("startup-gap-scan-fill-size"))).thenReturn(10_000);
        when(this.jdbcTemplate.queryForObject(eq("SELECT value FROM c_configuration WHERE name = ?"), eq(Integer.class),
                eq("account-number-gap-refill-max-windows"))).thenReturn(50);
        when(this.jdbcTemplate.queryForObject(eq("SELECT next_value FROM m_account_number_sequence WHERE scope_key = ?"), eq(Long.class),
                eq("CLIENT:GLOBAL:GAP_SCAN"))).thenReturn(1L);
        when(this.jdbcTemplate.update(startsWith("INSERT IGNORE INTO m_account_number_sequence"), eq(1L), eq(1L), eq(10_000L), eq(1L),
                eq(10_000L), eq(10_000L), eq(100))).thenReturn(1);
        when(this.jdbcTemplate.update(startsWith("INSERT INTO m_account_number_sequence (scope_key, next_value) VALUES (?, ?)"),
                eq("CLIENT:GLOBAL:GAP_SCAN"), eq(10_001L), eq(10_001L))).thenReturn(1);

        this.gapPoolManager.refillGapPoolInternal();

        verify(this.jdbcTemplate).update(startsWith("INSERT IGNORE INTO m_account_number_sequence"), eq(1L), eq(1L), eq(10_000L), eq(1L),
                eq(10_000L), eq(10_000L), eq(100));
    }

    @Test
    void reclaimOrphanedClaims_updatesExpiredClaimRows() {
        when(this.jdbcTemplate.queryForObject(eq("SELECT enabled FROM c_configuration WHERE name = ?"), eq(Boolean.class),
                eq("account-number-reuse-gaps"))).thenReturn(true);
        when(this.jdbcTemplate.queryForObject(eq("SELECT value FROM c_configuration WHERE name = ?"), eq(Integer.class),
                eq("account-number-gap-claim-timeout-minutes"))).thenReturn(45);

        this.gapPoolManager.reclaimOrphanedClaims();

        verify(this.jdbcTemplate).update(startsWith("UPDATE m_account_number_sequence"), eq("CLIENT:GLOBAL:CLAIM:%"), eq(45));
    }

    @Test
    void runClusterSafeGapMaintenance_skipsWhenClusterLockNotAcquired() {
        when(this.jdbcTemplate.queryForObject(eq("SELECT enabled FROM c_configuration WHERE name = ?"), eq(Boolean.class),
                eq("account-number-reuse-gaps"))).thenReturn(true);
        when(this.jdbcTemplate.queryForObject(eq("SELECT GET_LOCK(?, 0)"), eq(Integer.class), eq("fineract:account_number_gap:default")))
                .thenReturn(0);

        this.gapPoolManager.runClusterSafeGapMaintenance();

        verify(this.jdbcTemplate, never()).update(startsWith("UPDATE m_account_number_sequence"), eq("CLIENT:GLOBAL:CLAIM:%"), any());
    }

    @Test
    void runClusterSafeGapMaintenance_reclaimsAndRefillsWhenLockAcquired() {
        when(this.jdbcTemplate.queryForObject(eq("SELECT enabled FROM c_configuration WHERE name = ?"), eq(Boolean.class),
                eq("account-number-reuse-gaps"))).thenReturn(true);
        when(this.jdbcTemplate.queryForObject(eq("SELECT GET_LOCK(?, 0)"), eq(Integer.class), eq("fineract:account_number_gap:default")))
                .thenReturn(1);
        when(this.jdbcTemplate.queryForObject(eq("SELECT RELEASE_LOCK(?)"), eq(Integer.class), eq("fineract:account_number_gap:default")))
                .thenReturn(1);
        when(this.jdbcTemplate.queryForObject(eq("SELECT value FROM c_configuration WHERE name = ?"), eq(Integer.class),
                eq("account-number-gap-claim-timeout-minutes"))).thenReturn(60);
        when(this.jdbcTemplate.queryForObject(eq("SELECT COUNT(*) FROM m_account_number_sequence WHERE scope_key LIKE ?"), eq(Integer.class),
                eq("CLIENT:GLOBAL:GAP:%"))).thenReturn(0, 5, 10);
        when(this.jdbcTemplate.queryForObject(startsWith("SELECT COALESCE(MAX("), eq(Long.class))).thenReturn(100L);
        when(this.jdbcTemplate.queryForObject(eq("SELECT value FROM c_configuration WHERE name = ?"), eq(Integer.class),
                eq("account-number-gap-pool-low-watermark"))).thenReturn(10);
        when(this.jdbcTemplate.queryForObject(eq("SELECT value FROM c_configuration WHERE name = ?"), eq(Integer.class),
                eq("account-number-gap-pool-batch-size"))).thenReturn(100);
        when(this.jdbcTemplate.queryForObject(eq("SELECT value FROM c_configuration WHERE name = ?"), eq(Integer.class),
                eq("startup-gap-scan-fill-size"))).thenReturn(2000);
        when(this.jdbcTemplate.queryForObject(eq("SELECT value FROM c_configuration WHERE name = ?"), eq(Integer.class),
                eq("account-number-gap-refill-max-windows"))).thenReturn(50);
        lenient().when(this.jdbcTemplate.queryForObject(eq("SELECT next_value FROM m_account_number_sequence WHERE scope_key = ?"),
                eq(Long.class), eq("CLIENT:GLOBAL:GAP_SCAN"))).thenReturn(1L);
        lenient().when(this.jdbcTemplate.update(startsWith("INSERT IGNORE INTO m_account_number_sequence"), any(), any(), any(), any(), any(),
                any(), any())).thenReturn(0);
        lenient().when(this.jdbcTemplate.update(startsWith("INSERT INTO m_account_number_sequence (scope_key, next_value) VALUES (?, ?)"),
                eq("CLIENT:GLOBAL:GAP_SCAN"), any(), any())).thenReturn(1);

        this.gapPoolManager.runClusterSafeGapMaintenance();

        verify(this.jdbcTemplate).update(startsWith("UPDATE m_account_number_sequence"), eq("CLIENT:GLOBAL:CLAIM:%"), eq(60));
        verify(this.jdbcTemplate).queryForObject(eq("SELECT RELEASE_LOCK(?)"), eq(Integer.class), eq("fineract:account_number_gap:default"));
    }
}
