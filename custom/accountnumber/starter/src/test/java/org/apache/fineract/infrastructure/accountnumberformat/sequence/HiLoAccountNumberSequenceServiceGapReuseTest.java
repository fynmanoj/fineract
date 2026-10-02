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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;

@ExtendWith(MockitoExtension.class)
class HiLoAccountNumberSequenceServiceGapReuseTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private AccountNumberSequenceAutoConfiguration.HiLoAccountNumberSequenceService service;

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
        final AccountNumberGapPoolManager gapPoolManager = new AccountNumberGapPoolManager(this.jdbcTemplate, transactionManager,
                Runnable::run);
        this.service = new AccountNumberSequenceAutoConfiguration.HiLoAccountNumberSequenceService(gapPoolManager, transactionManager);
    }

    @Test
    void nextValue_gapReuseDisabled_usesHiLoBlock() {
        stubBootstrap();
        when(this.jdbcTemplate.queryForObject(eq("SELECT enabled FROM c_configuration WHERE name = ?"), eq(Boolean.class),
                eq("account-number-reuse-gaps"))).thenThrow(new EmptyResultDataAccessException(1));
        when(this.jdbcTemplate.update(eq("UPDATE m_account_number_sequence SET next_value = LAST_INSERT_ID(next_value + ?) WHERE scope_key = ?"),
                eq(10), eq("CLIENT:GLOBAL"))).thenReturn(1);
        when(this.jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class)).thenReturn(11L);

        assertEquals(1L, this.service.nextValue(10));
    }

    @Test
    void nextValue_gapReuseEnabled_takesLowestGapRow() {
        stubBootstrap();
        stubGapRefillConfig();
        when(this.jdbcTemplate.queryForObject(eq("SELECT enabled FROM c_configuration WHERE name = ?"), eq(Boolean.class),
                eq("account-number-reuse-gaps"))).thenReturn(true);

        when(this.jdbcTemplate.queryForList(
                eq("SELECT id, next_value FROM m_account_number_sequence WHERE scope_key LIKE ? ORDER BY next_value ASC LIMIT 1 FOR UPDATE"),
                eq(AccountNumberSequenceAutoConfiguration.GAP_SCOPE_PREFIX + "%")))
                .thenReturn(Collections.singletonList(Map.of("id", 99L, "next_value", 3L)));
        when(this.jdbcTemplate.update(eq("UPDATE m_account_number_sequence SET scope_key = ?, claimed_at = NOW() WHERE id = ?"), anyString(),
                eq(99L))).thenReturn(1);

        assertEquals(3L, this.service.nextValue(10));
    }

    @Test
    void nextValue_noGapRows_fallsBackToHiLoWithoutBlockingRefill() {
        stubBootstrap();
        stubGapRefillConfig();
        stubGapScanCursor();
        stubClusterLock();
        lenient().when(this.jdbcTemplate.update(startsWith("INSERT IGNORE INTO m_account_number_sequence"), any(), any(), any(), any(), any(),
                any(), any())).thenReturn(0);
        when(this.jdbcTemplate.queryForObject(eq("SELECT enabled FROM c_configuration WHERE name = ?"), eq(Boolean.class),
                eq("account-number-reuse-gaps"))).thenReturn(true);
        when(this.jdbcTemplate.queryForList(
                eq("SELECT id, next_value FROM m_account_number_sequence WHERE scope_key LIKE ? ORDER BY next_value ASC LIMIT 1 FOR UPDATE"),
                eq(AccountNumberSequenceAutoConfiguration.GAP_SCOPE_PREFIX + "%"))).thenReturn(Collections.emptyList());
        when(this.jdbcTemplate.update(eq("UPDATE m_account_number_sequence SET next_value = LAST_INSERT_ID(next_value + ?) WHERE scope_key = ?"),
                eq(10), eq("CLIENT:GLOBAL"))).thenReturn(1);
        when(this.jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class)).thenReturn(11L);

        assertEquals(1L, this.service.nextValue(10));
        verify(this.jdbcTemplate).update(eq("UPDATE m_account_number_sequence SET next_value = LAST_INSERT_ID(next_value + ?) WHERE scope_key = ?"),
                eq(10), eq("CLIENT:GLOBAL"));
    }

    @Test
    void nextValue_gapReuseEnabled_refillsAndUsesLowestGap() {
        stubBootstrapWithMaxSuffix(3L);
        stubGapRefillConfig();
        stubGapScanCursor();
        when(this.jdbcTemplate.queryForObject(eq("SELECT enabled FROM c_configuration WHERE name = ?"), eq(Boolean.class),
                eq("account-number-reuse-gaps"))).thenReturn(true);
        when(this.jdbcTemplate.queryForList(
                eq("SELECT id, next_value FROM m_account_number_sequence WHERE scope_key LIKE ? ORDER BY next_value ASC LIMIT 1 FOR UPDATE"),
                eq(AccountNumberSequenceAutoConfiguration.GAP_SCOPE_PREFIX + "%")))
                .thenReturn(Collections.singletonList(Map.of("id", 42L, "next_value", 2L)));
        when(this.jdbcTemplate.queryForObject(eq("SELECT COUNT(*) FROM m_account_number_sequence WHERE scope_key LIKE ?"), eq(Integer.class),
                eq(AccountNumberSequenceAutoConfiguration.GAP_SCOPE_PREFIX + "%"))).thenReturn(0, 1, 1, 1);
        stubClusterLock();
        lenient().when(this.jdbcTemplate.update(startsWith("INSERT IGNORE INTO m_account_number_sequence"), any(), any(), any(), any(), any(),
                any(), any())).thenReturn(1, 0, 0);
        when(this.jdbcTemplate.update(eq("UPDATE m_account_number_sequence SET scope_key = ?, claimed_at = NOW() WHERE id = ?"), anyString(),
                eq(42L))).thenReturn(1);

        assertEquals(2L, this.service.nextValue(10));
        verify(this.jdbcTemplate, atLeast(1)).update(startsWith("INSERT IGNORE INTO m_account_number_sequence"), any(), any(), any(), any(),
                any(), any(), any());
    }

    @Test
    void nextValue_gapReuseEnabled_refillsWhenPoolBelowWatermark() {
        stubBootstrapWithMaxSuffix(20L);
        stubGapScanCursor();
        stubClusterLock();
        when(this.jdbcTemplate.queryForObject(eq("SELECT enabled FROM c_configuration WHERE name = ?"), eq(Boolean.class),
                eq("account-number-reuse-gaps"))).thenReturn(true);
        when(this.jdbcTemplate.queryForObject(eq("SELECT COUNT(*) FROM m_account_number_sequence WHERE scope_key LIKE ?"), eq(Integer.class),
                eq(AccountNumberSequenceAutoConfiguration.GAP_SCOPE_PREFIX + "%"))).thenReturn(3, 8, 12, 12);
        lenient().when(this.jdbcTemplate.queryForObject(eq("SELECT value FROM c_configuration WHERE name = ?"), eq(Integer.class),
                eq("account-number-gap-pool-low-watermark"))).thenReturn(10);
        lenient().when(this.jdbcTemplate.queryForObject(eq("SELECT value FROM c_configuration WHERE name = ?"), eq(Integer.class),
                eq("account-number-gap-pool-batch-size"))).thenReturn(5);
        lenient().when(this.jdbcTemplate.queryForObject(eq("SELECT value FROM c_configuration WHERE name = ?"), eq(Integer.class),
                eq("account-number-gap-claim-timeout-minutes"))).thenReturn(60);
        lenient().when(this.jdbcTemplate.queryForObject(eq("SELECT value FROM c_configuration WHERE name = ?"), eq(Integer.class),
                eq("startup-gap-scan-fill-size"))).thenReturn(2000);
        lenient().when(this.jdbcTemplate.queryForObject(eq("SELECT value FROM c_configuration WHERE name = ?"), eq(Integer.class),
                eq("account-number-gap-refill-max-windows"))).thenReturn(50);
        lenient().when(this.jdbcTemplate.update(startsWith("INSERT IGNORE INTO m_account_number_sequence"), any(), any(), any(), any(), any(),
                any(), any())).thenReturn(5, 4, 0, 0);
        when(this.jdbcTemplate.queryForList(
                eq("SELECT id, next_value FROM m_account_number_sequence WHERE scope_key LIKE ? ORDER BY next_value ASC LIMIT 1 FOR UPDATE"),
                eq(AccountNumberSequenceAutoConfiguration.GAP_SCOPE_PREFIX + "%")))
                .thenReturn(Collections.singletonList(Map.of("id", 7L, "next_value", 4L)));
        when(this.jdbcTemplate.update(eq("UPDATE m_account_number_sequence SET scope_key = ?, claimed_at = NOW() WHERE id = ?"), anyString(),
                eq(7L))).thenReturn(1);

        assertEquals(4L, this.service.nextValue(10));
        verify(this.jdbcTemplate, times(2)).update(startsWith("INSERT IGNORE INTO m_account_number_sequence"), any(), any(), any(), any(),
                any(), any(), any());
    }

    @Test
    void nextValue_gapReuseEnabled_doesNotBlockOnRefillWhenGapsAvailable() {
        stubBootstrap();
        when(this.jdbcTemplate.queryForObject(eq("SELECT enabled FROM c_configuration WHERE name = ?"), eq(Boolean.class),
                eq("account-number-reuse-gaps"))).thenReturn(true);
        when(this.jdbcTemplate.queryForList(
                eq("SELECT id, next_value FROM m_account_number_sequence WHERE scope_key LIKE ? ORDER BY next_value ASC LIMIT 1 FOR UPDATE"),
                eq(AccountNumberSequenceAutoConfiguration.GAP_SCOPE_PREFIX + "%")))
                .thenReturn(Collections.singletonList(Map.of("id", 5L, "next_value", 7L)));
        when(this.jdbcTemplate.update(eq("UPDATE m_account_number_sequence SET scope_key = ?, claimed_at = NOW() WHERE id = ?"), anyString(),
                eq(5L))).thenReturn(1);

        assertEquals(7L, this.service.nextValue(10));
        verify(this.jdbcTemplate, never()).update(startsWith("INSERT IGNORE INTO m_account_number_sequence"), any(), any(), any(), any(), any(),
                any(), any());
    }

    @Test
    void nextValue_gapClaimWithoutTransaction_deletesClaimRowImmediately() {
        stubBootstrap();
        stubGapRefillConfig();
        when(this.jdbcTemplate.queryForObject(eq("SELECT enabled FROM c_configuration WHERE name = ?"), eq(Boolean.class),
                eq("account-number-reuse-gaps"))).thenReturn(true);
        when(this.jdbcTemplate.queryForList(
                eq("SELECT id, next_value FROM m_account_number_sequence WHERE scope_key LIKE ? ORDER BY next_value ASC LIMIT 1 FOR UPDATE"),
                eq(AccountNumberSequenceAutoConfiguration.GAP_SCOPE_PREFIX + "%")))
                .thenReturn(Collections.singletonList(Map.of("id", 11L, "next_value", 8L)));
        when(this.jdbcTemplate.update(eq("UPDATE m_account_number_sequence SET scope_key = ?, claimed_at = NOW() WHERE id = ?"), anyString(),
                eq(11L))).thenReturn(1);
        when(this.jdbcTemplate.update(eq("DELETE FROM m_account_number_sequence WHERE scope_key = ?"), eq("CLIENT:GLOBAL:CLAIM:8")))
                .thenReturn(1);

        assertEquals(8L, this.service.nextValue(10));
        verify(this.jdbcTemplate).update(eq("DELETE FROM m_account_number_sequence WHERE scope_key = ?"), eq("CLIENT:GLOBAL:CLAIM:8"));
    }

    @Test
    void nextValue_afterBootstrap_allocatesNextSequentialNumberWithoutSkipping() {
        stubBootstrapWithMaxSuffix(3L);
        when(this.jdbcTemplate.queryForObject(eq("SELECT enabled FROM c_configuration WHERE name = ?"), eq(Boolean.class),
                eq("account-number-reuse-gaps"))).thenReturn(false);
        when(this.jdbcTemplate.update(eq("UPDATE m_account_number_sequence SET next_value = LAST_INSERT_ID(next_value + ?) WHERE scope_key = ?"),
                eq(10), eq("CLIENT:GLOBAL"))).thenReturn(1);
        when(this.jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class)).thenReturn(14L);

        assertEquals(4L, this.service.nextValue(10));
    }

    private void stubBootstrap() {
        stubBootstrapWithMaxSuffix(0L);
    }

    private void stubGapRefillConfig() {
        stubClusterLock();
        lenient().when(this.jdbcTemplate.queryForObject(eq("SELECT value FROM c_configuration WHERE name = ?"), eq(Integer.class),
                eq("account-number-gap-pool-low-watermark"))).thenReturn(10);
        lenient().when(this.jdbcTemplate.queryForObject(eq("SELECT value FROM c_configuration WHERE name = ?"), eq(Integer.class),
                eq("account-number-gap-pool-batch-size"))).thenReturn(100);
        lenient().when(this.jdbcTemplate.queryForObject(eq("SELECT value FROM c_configuration WHERE name = ?"), eq(Integer.class),
                eq("account-number-gap-claim-timeout-minutes"))).thenReturn(60);
        lenient().when(this.jdbcTemplate.queryForObject(eq("SELECT value FROM c_configuration WHERE name = ?"), eq(Integer.class),
                eq("startup-gap-scan-fill-size"))).thenReturn(2000);
        lenient().when(this.jdbcTemplate.queryForObject(eq("SELECT value FROM c_configuration WHERE name = ?"), eq(Integer.class),
                eq("account-number-gap-refill-max-windows"))).thenReturn(50);
        lenient().when(this.jdbcTemplate.queryForObject(eq("SELECT COUNT(*) FROM m_account_number_sequence WHERE scope_key LIKE ?"),
                eq(Integer.class), eq(AccountNumberSequenceAutoConfiguration.GAP_SCOPE_PREFIX + "%"))).thenReturn(10);
        lenient().when(this.jdbcTemplate.update(startsWith("UPDATE m_account_number_sequence"), eq("CLIENT:GLOBAL:CLAIM:%"), any()))
                .thenReturn(0);
    }

    private void stubClusterLock() {
        lenient().when(this.jdbcTemplate.queryForObject(eq("SELECT GET_LOCK(?, 0)"), eq(Integer.class), anyString())).thenReturn(1);
        lenient().when(this.jdbcTemplate.queryForObject(eq("SELECT RELEASE_LOCK(?)"), eq(Integer.class), anyString())).thenReturn(1);
    }

    private void stubGapScanCursor() {
        lenient().when(this.jdbcTemplate.queryForObject(
                eq("SELECT next_value FROM m_account_number_sequence WHERE scope_key = ?"), eq(Long.class), eq("CLIENT:GLOBAL:GAP_SCAN")))
                .thenReturn(1L);
        lenient().when(this.jdbcTemplate.update(startsWith("INSERT INTO m_account_number_sequence (scope_key, next_value) VALUES (?, ?)"),
                eq("CLIENT:GLOBAL:GAP_SCAN"), anyLong(), anyLong())).thenReturn(1);
    }

    private void stubBootstrapWithMaxSuffix(final long maxSuffix) {
        final long minimumNext = maxSuffix + 1;
        lenient().when(this.jdbcTemplate.queryForObject(startsWith("SELECT COALESCE(MAX("), eq(Long.class))).thenReturn(maxSuffix);
        lenient().when(this.jdbcTemplate.update(startsWith("INSERT INTO m_account_number_sequence"), eq("CLIENT:GLOBAL"), eq(minimumNext),
                eq(minimumNext))).thenReturn(1);
    }
}
