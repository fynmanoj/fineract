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

import static org.apache.fineract.infrastructure.accountnumberformat.sequence.AccountNumberGapSqlSupport.CLAIM_SCOPE_PREFIX;
import static org.apache.fineract.infrastructure.accountnumberformat.sequence.AccountNumberGapSqlSupport.CLIENT_GLOBAL_SCOPE_KEY;
import static org.apache.fineract.infrastructure.accountnumberformat.sequence.AccountNumberGapSqlSupport.CLUSTER_GAP_LOCK_PREFIX;
import static org.apache.fineract.infrastructure.accountnumberformat.sequence.AccountNumberGapSqlSupport.CONFIG_GAP_BATCH_SIZE;
import static org.apache.fineract.infrastructure.accountnumberformat.sequence.AccountNumberGapSqlSupport.CONFIG_GAP_CLAIM_TIMEOUT_MINUTES;
import static org.apache.fineract.infrastructure.accountnumberformat.sequence.AccountNumberGapSqlSupport.CONFIG_GAP_LOW_WATERMARK;
import static org.apache.fineract.infrastructure.accountnumberformat.sequence.AccountNumberGapSqlSupport.CONFIG_GAP_REFILL_MAX_WINDOWS;
import static org.apache.fineract.infrastructure.accountnumberformat.sequence.AccountNumberGapSqlSupport.CONFIG_REUSE_GAPS;
import static org.apache.fineract.infrastructure.accountnumberformat.sequence.AccountNumberGapSqlSupport.CONFIG_STARTUP_GAP_SCAN_FILL_SIZE;
import static org.apache.fineract.infrastructure.accountnumberformat.sequence.AccountNumberGapSqlSupport.DEFAULT_GAP_BATCH_SIZE;
import static org.apache.fineract.infrastructure.accountnumberformat.sequence.AccountNumberGapSqlSupport.DEFAULT_GAP_CLAIM_TIMEOUT_MINUTES;
import static org.apache.fineract.infrastructure.accountnumberformat.sequence.AccountNumberGapSqlSupport.DEFAULT_GAP_LOW_WATERMARK;
import static org.apache.fineract.infrastructure.accountnumberformat.sequence.AccountNumberGapSqlSupport.DEFAULT_GAP_REFILL_MAX_WINDOWS;
import static org.apache.fineract.infrastructure.accountnumberformat.sequence.AccountNumberGapSqlSupport.DEFAULT_STARTUP_GAP_SCAN_FILL_SIZE;
import static org.apache.fineract.infrastructure.accountnumberformat.sequence.AccountNumberGapSqlSupport.GAP_SCAN_CURSOR_KEY;
import static org.apache.fineract.infrastructure.accountnumberformat.sequence.AccountNumberGapSqlSupport.GAP_SCOPE_PREFIX;
import static org.apache.fineract.infrastructure.accountnumberformat.sequence.AccountNumberGapSqlSupport.INSERT_GAP_ROWS_SQL;
import static org.apache.fineract.infrastructure.accountnumberformat.sequence.AccountNumberGapSqlSupport.MAX_NUMERIC_SUFFIX_SQL;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.fineract.infrastructure.core.domain.FineractContext;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

class AccountNumberGapPoolManager {

    private static final Logger log = LoggerFactory.getLogger(AccountNumberGapPoolManager.class);

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate requiresNewTemplate;
    private final ReentrantLock gapRefillLock = new ReentrantLock();
    private final Executor gapRefillExecutor;

    AccountNumberGapPoolManager(final JdbcTemplate jdbcTemplate, final PlatformTransactionManager transactionManager) {
        this(jdbcTemplate, transactionManager, CompletableFuture::runAsync);
    }

    AccountNumberGapPoolManager(final JdbcTemplate jdbcTemplate, final PlatformTransactionManager transactionManager,
            final Executor gapRefillExecutor) {
        this.jdbcTemplate = jdbcTemplate;
        this.gapRefillExecutor = gapRefillExecutor;
        this.requiresNewTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    boolean isGapReuseEnabled() {
        try {
            final Boolean enabled = this.jdbcTemplate.queryForObject("SELECT enabled FROM c_configuration WHERE name = ?", Boolean.class,
                    CONFIG_REUSE_GAPS);
            return Boolean.TRUE.equals(enabled);
        } catch (EmptyResultDataAccessException e) {
            return false;
        }
    }

    int getLowWatermark() {
        return getConfigInt(CONFIG_GAP_LOW_WATERMARK, DEFAULT_GAP_LOW_WATERMARK);
    }

    Long takeLowestGapRow() {
        return this.requiresNewTemplate.execute(status -> {
            final List<Map<String, Object>> rows = this.jdbcTemplate.queryForList(
                    "SELECT id, next_value FROM m_account_number_sequence WHERE scope_key LIKE ? ORDER BY next_value ASC LIMIT 1 FOR UPDATE",
                    GAP_SCOPE_PREFIX + "%");
            if (rows.isEmpty()) {
                return null;
            }
            final Number id = (Number) rows.get(0).get("id");
            final Number nextValue = (Number) rows.get(0).get("next_value");
            if (id == null || nextValue == null) {
                return null;
            }
            final long accountNumber = nextValue.longValue();
            this.jdbcTemplate.update("UPDATE m_account_number_sequence SET scope_key = ?, claimed_at = NOW() WHERE id = ?",
                    CLAIM_SCOPE_PREFIX + accountNumber, id.longValue());
            return accountNumber;
        });
    }

    void scheduleAsyncGapRefill() {
        scheduleAsyncGapRefillWithContext(captureTenantContext());
    }

    void scheduleAsyncGapRefillWithContext(final FineractContext context) {
        if (!isGapReuseEnabled()) {
            return;
        }
        if (!this.gapRefillLock.tryLock()) {
            return;
        }
        this.gapRefillExecutor.execute(() -> {
            try {
                if (context != null) {
                    ThreadLocalContextUtil.init(context);
                }
                runClusterSafeGapMaintenance();
            } catch (Exception e) {
                log.error("Gap pool async refill failed", e);
            } finally {
                ThreadLocalContextUtil.reset();
                this.gapRefillLock.unlock();
            }
        });
    }

    void runClusterSafeGapMaintenance() {
        if (!isGapReuseEnabled()) {
            return;
        }
        this.requiresNewTemplate.execute(status -> {
            final String lockName = clusterLockName();
            final Integer acquired = this.jdbcTemplate.queryForObject("SELECT GET_LOCK(?, 0)", Integer.class, lockName);
            if (acquired == null || acquired != 1) {
                log.debug("Gap maintenance skipped; cluster lock not acquired for {}", lockName);
                return null;
            }
            try {
                executeReclaimOrphanedClaims();
                executeRefillGapPool();
            } finally {
                this.jdbcTemplate.queryForObject("SELECT RELEASE_LOCK(?)", Integer.class, lockName);
            }
            return null;
        });
    }

    void reclaimOrphanedClaims() {
        if (!isGapReuseEnabled()) {
            return;
        }
        this.requiresNewTemplate.execute(status -> {
            executeReclaimOrphanedClaims();
            return null;
        });
    }

    void refillGapPoolInternal() {
        if (!isGapReuseEnabled()) {
            return;
        }
        this.requiresNewTemplate.execute(status -> {
            executeRefillGapPool();
            return null;
        });
    }

    void persistReclaimedGap(final long value) {
        if (!isGapReuseEnabled()) {
            return;
        }
        this.jdbcTemplate.update("INSERT IGNORE INTO m_account_number_sequence (scope_key, next_value) VALUES (?, ?)",
                GAP_SCOPE_PREFIX + value, value);
    }

    void ensureGlobalPointer() {
        this.requiresNewTemplate.execute(status -> {
            upsertGlobalPointer();
            return null;
        });
    }

    void upsertGlobalPointer() {
        final long minimumNext = queryMaxClientNumericSuffix() + 1;
        this.jdbcTemplate.update(
                "INSERT INTO m_account_number_sequence (scope_key, next_value) VALUES (?, ?) "
                        + "ON DUPLICATE KEY UPDATE next_value = GREATEST(next_value, ?)",
                CLIENT_GLOBAL_SCOPE_KEY, minimumNext, minimumNext);
    }

    int countGapRows() {
        final Integer count = this.jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM m_account_number_sequence WHERE scope_key LIKE ?", Integer.class, GAP_SCOPE_PREFIX + "%");
        return count == null ? 0 : count;
    }

    FineractContext captureTenantContext() {
        final FineractPlatformTenant tenant = ThreadLocalContextUtil.getTenant();
        if (tenant == null) {
            return null;
        }
        return ThreadLocalContextUtil.getContext();
    }

    JdbcTemplate getJdbcTemplate() {
        return this.jdbcTemplate;
    }

    TransactionTemplate getRequiresNewTemplate() {
        return this.requiresNewTemplate;
    }

    private void executeReclaimOrphanedClaims() {
        final int timeoutMinutes = getConfigInt(CONFIG_GAP_CLAIM_TIMEOUT_MINUTES, DEFAULT_GAP_CLAIM_TIMEOUT_MINUTES);
        if (timeoutMinutes <= 0) {
            return;
        }
        this.jdbcTemplate.update("""
                UPDATE m_account_number_sequence
                SET scope_key = CONCAT('CLIENT:GLOBAL:GAP:', next_value), claimed_at = NULL
                WHERE scope_key LIKE ?
                  AND claimed_at IS NOT NULL
                  AND claimed_at < NOW() - INTERVAL ? MINUTE
                """, CLAIM_SCOPE_PREFIX + "%", timeoutMinutes);
    }

    private void executeRefillGapPool() {
        final int lowWatermark = getLowWatermark();
        final int batchSize = getConfigInt(CONFIG_GAP_BATCH_SIZE, DEFAULT_GAP_BATCH_SIZE);
        final long maxSuffix = queryMaxClientNumericSuffix();
        if (maxSuffix <= 0 || batchSize <= 0) {
            return;
        }
        final int maxWindows = getGapRefillMaxWindows();
        final long cycleStartCursor = getOrCreateGapScanCursor();
        int windowsWithoutProgress = 0;
        while (countGapRows() < lowWatermark) {
            final int inserted = insertGapRows(maxSuffix, batchSize);
            if (inserted > 0) {
                windowsWithoutProgress = 0;
                continue;
            }
            windowsWithoutProgress++;
            final long currentCursor = getOrCreateGapScanCursor();
            if (windowsWithoutProgress > 1 && currentCursor <= cycleStartCursor) {
                break;
            }
            if (windowsWithoutProgress >= maxWindows) {
                break;
            }
        }
    }

    private int getGapRefillMaxWindows() {
        return getConfigInt(CONFIG_GAP_REFILL_MAX_WINDOWS, DEFAULT_GAP_REFILL_MAX_WINDOWS);
    }

    private int getStartupGapScanFillSize() {
        return getConfigInt(CONFIG_STARTUP_GAP_SCAN_FILL_SIZE, DEFAULT_STARTUP_GAP_SCAN_FILL_SIZE);
    }

    private String clusterLockName() {
        final FineractPlatformTenant tenant = ThreadLocalContextUtil.getTenant();
        final String tenantId = tenant == null ? "default" : tenant.getTenantIdentifier();
        return CLUSTER_GAP_LOCK_PREFIX + tenantId;
    }

    private int getConfigInt(final String name, final int defaultValue) {
        try {
            final Integer value = this.jdbcTemplate.queryForObject("SELECT value FROM c_configuration WHERE name = ?", Integer.class, name);
            return value == null ? defaultValue : value;
        } catch (EmptyResultDataAccessException e) {
            return defaultValue;
        }
    }

    private int insertGapRows(final long maxSuffix, final int batchSize) {
        if (batchSize <= 0 || maxSuffix <= 0) {
            return 0;
        }
        final long cursor = getOrCreateGapScanCursor();
        if (cursor > maxSuffix) {
            updateGapScanCursor(1L);
            return 0;
        }
        final int scanWindowSize = getStartupGapScanFillSize();
        if (scanWindowSize <= 0) {
            return 0;
        }
        final long scanEnd = Math.min(cursor + scanWindowSize - 1L, maxSuffix);
        final int inserted = this.jdbcTemplate.update(INSERT_GAP_ROWS_SQL, cursor, cursor, scanEnd, cursor, scanEnd, scanEnd, batchSize);
        updateGapScanCursor(scanEnd >= maxSuffix ? 1L : scanEnd + 1);
        return inserted;
    }

    private long getOrCreateGapScanCursor() {
        try {
            final Long cursor = this.jdbcTemplate.queryForObject("SELECT next_value FROM m_account_number_sequence WHERE scope_key = ?",
                    Long.class, GAP_SCAN_CURSOR_KEY);
            return cursor == null || cursor < 1 ? 1L : cursor;
        } catch (EmptyResultDataAccessException e) {
            this.jdbcTemplate.update("INSERT IGNORE INTO m_account_number_sequence (scope_key, next_value) VALUES (?, 1)", GAP_SCAN_CURSOR_KEY);
            return 1L;
        }
    }

    private void updateGapScanCursor(final long nextCursor) {
        this.jdbcTemplate.update(
                "INSERT INTO m_account_number_sequence (scope_key, next_value) VALUES (?, ?) "
                        + "ON DUPLICATE KEY UPDATE next_value = ?",
                GAP_SCAN_CURSOR_KEY, nextCursor, nextCursor);
    }

    private long queryMaxClientNumericSuffix() {
        final Long maxExisting = this.jdbcTemplate.queryForObject(MAX_NUMERIC_SUFFIX_SQL, Long.class);
        return maxExisting == null ? 0L : maxExisting;
    }
}
