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
import static org.apache.fineract.infrastructure.accountnumberformat.sequence.AccountNumberGapSqlSupport.GAP_SCOPE_PREFIX;
import static org.apache.fineract.infrastructure.accountnumberformat.sequence.AccountNumberGapSqlSupport.MAX_NUMERIC_ACCOUNT_NUMBER;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.fineract.infrastructure.core.domain.FineractContext;
import org.apache.fineract.portfolio.client.exception.AccountNumberOverflowException;
import org.apache.fineract.portfolio.client.service.AccountNumberSequenceService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfiguration
public class AccountNumberSequenceAutoConfiguration {

    static final String GAP_SCOPE_PREFIX = AccountNumberGapSqlSupport.GAP_SCOPE_PREFIX;
    static final String CLAIM_SCOPE_PREFIX = AccountNumberGapSqlSupport.CLAIM_SCOPE_PREFIX;

    private static final Object GAP_REFILL_SCHEDULED_KEY = new Object();

    @Bean
    AccountNumberGapPoolManager accountNumberGapPoolManager(final JdbcTemplate jdbcTemplate,
            final PlatformTransactionManager transactionManager) {
        return new AccountNumberGapPoolManager(jdbcTemplate, transactionManager);
    }

    @Bean
    @Primary
    public AccountNumberSequenceService hiLoAccountNumberSequenceService(final AccountNumberGapPoolManager gapPoolManager,
            final PlatformTransactionManager transactionManager) {
        return new HiLoAccountNumberSequenceService(gapPoolManager, transactionManager);
    }

    static final class HiLoAccountNumberSequenceService implements AccountNumberSequenceService {

        private final AccountNumberGapPoolManager gapPoolManager;
        private final TransactionTemplate requiresNewTemplate;
        private final Map<String, HiLoBlock> blocks = new ConcurrentHashMap<>();
        private final Map<String, Queue<Long>> reclaimPools = new ConcurrentHashMap<>();
        private final Map<String, ReentrantLock> refillLocks = new ConcurrentHashMap<>();

        HiLoAccountNumberSequenceService(final AccountNumberGapPoolManager gapPoolManager,
                final PlatformTransactionManager transactionManager) {
            this.gapPoolManager = gapPoolManager;
            this.requiresNewTemplate = gapPoolManager.getRequiresNewTemplate();
        }

        @Override
        public long nextValue(final int blockSize) {
            if (blockSize <= 0) {
                throw new IllegalArgumentException("blockSize must be positive");
            }
            if (this.gapPoolManager.isGapReuseEnabled()) {
                final Long reclaimed = pollReclaim(CLIENT_GLOBAL_SCOPE_KEY);
                if (reclaimed != null) {
                    validateOverflow(reclaimed);
                    return registerCompletionHandler(CLIENT_GLOBAL_SCOPE_KEY, reclaimed, false);
                }

                final Long gap = this.gapPoolManager.takeLowestGapRow();
                if (gap != null) {
                    validateOverflow(gap);
                    scheduleGapRefillAfterTransaction();
                    return registerCompletionHandler(CLIENT_GLOBAL_SCOPE_KEY, gap, true);
                }
                this.gapPoolManager.scheduleAsyncGapRefill();
            }

            final Long reclaimed = pollReclaim(CLIENT_GLOBAL_SCOPE_KEY);
            if (reclaimed != null) {
                validateOverflow(reclaimed);
                return registerCompletionHandler(CLIENT_GLOBAL_SCOPE_KEY, reclaimed, false);
            }

            HiLoBlock block = this.blocks.get(CLIENT_GLOBAL_SCOPE_KEY);
            if (block != null) {
                final long candidate = block.getAndIncrement();
                if (block.hasCapacity(candidate)) {
                    validateOverflow(candidate);
                    return registerCompletionHandler(CLIENT_GLOBAL_SCOPE_KEY, candidate, false);
                }
            }

            this.gapPoolManager.ensureGlobalPointer();
            final long allocated = allocateFromRefill(blockSize);
            validateOverflow(allocated);
            return registerCompletionHandler(CLIENT_GLOBAL_SCOPE_KEY, allocated, false);
        }

        private void scheduleGapRefillAfterTransaction() {
            if (!TransactionSynchronizationManager.isSynchronizationActive()) {
                this.gapPoolManager.scheduleAsyncGapRefill();
                return;
            }
            if (TransactionSynchronizationManager.getResource(GAP_REFILL_SCHEDULED_KEY) != null) {
                return;
            }
            final FineractContext context = this.gapPoolManager.captureTenantContext();
            TransactionSynchronizationManager.bindResource(GAP_REFILL_SCHEDULED_KEY, Boolean.TRUE);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

                @Override
                public void afterCompletion(final int status) {
                    TransactionSynchronizationManager.unbindResource(GAP_REFILL_SCHEDULED_KEY);
                    gapPoolManager.scheduleAsyncGapRefillWithContext(context);
                }
            });
        }

        private long allocateFromRefill(final int blockSize) {
            final ReentrantLock lock = this.refillLocks.computeIfAbsent(CLIENT_GLOBAL_SCOPE_KEY, key -> new ReentrantLock());
            lock.lock();
            try {
                HiLoBlock block = this.blocks.get(CLIENT_GLOBAL_SCOPE_KEY);
                if (block != null) {
                    final long candidate = block.getAndIncrement();
                    if (block.hasCapacity(candidate)) {
                        return candidate;
                    }
                }
                final long blockStart = allocateBlock(blockSize);
                final long blockEnd = blockStart + blockSize - 1;
                block = new HiLoBlock(blockStart, blockEnd);
                this.blocks.put(CLIENT_GLOBAL_SCOPE_KEY, block);
                return block.getAndIncrement();
            } finally {
                lock.unlock();
            }
        }

        private long allocateBlock(final int blockSize) {
            return this.requiresNewTemplate.execute(status -> {
                gapPoolManager.upsertGlobalPointer();
                final int updated = gapPoolManager.getJdbcTemplate().update(
                        "UPDATE m_account_number_sequence SET next_value = LAST_INSERT_ID(next_value + ?) WHERE scope_key = ?",
                        blockSize, CLIENT_GLOBAL_SCOPE_KEY);
                if (updated == 0) {
                    throw new IllegalStateException("CLIENT:GLOBAL sequence row is missing after upsert");
                }
                final Long newNextValue = gapPoolManager.getJdbcTemplate().queryForObject("SELECT LAST_INSERT_ID()", Long.class);
                return newNextValue - blockSize;
            });
        }

        private Long pollReclaim(final String scopeKey) {
            final Queue<Long> pool = this.reclaimPools.get(scopeKey);
            return pool == null ? null : pool.poll();
        }

        private long registerCompletionHandler(final String scopeKey, final long value, final boolean gapClaim) {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

                    @Override
                    public void afterCompletion(final int status) {
                        handleAllocationCompletion(scopeKey, value, gapClaim, status);
                    }
                });
            } else {
                handleAllocationCompletionWithoutTransaction(scopeKey, value, gapClaim);
            }
            return value;
        }

        private void handleAllocationCompletion(final String scopeKey, final long value, final boolean gapClaim, final int status) {
            if (gapClaim) {
                if (status == TransactionSynchronization.STATUS_COMMITTED) {
                    deleteClaimRow(value);
                } else if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                    restoreGapRow(scopeKey, value);
                }
            } else if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                reclaimPools.computeIfAbsent(scopeKey, key -> new ConcurrentLinkedQueue<>()).offer(value);
                gapPoolManager.persistReclaimedGap(value);
            }
        }

        private void handleAllocationCompletionWithoutTransaction(final String scopeKey, final long value, final boolean gapClaim) {
            if (gapClaim) {
                deleteClaimRow(value);
            }
        }

        private void deleteClaimRow(final long value) {
            this.gapPoolManager.getJdbcTemplate().update("DELETE FROM m_account_number_sequence WHERE scope_key = ?",
                    CLAIM_SCOPE_PREFIX + value);
        }

        private void restoreGapRow(final String scopeKey, final long value) {
            final int restored = this.gapPoolManager.getJdbcTemplate().update(
                    "UPDATE m_account_number_sequence SET scope_key = ?, claimed_at = NULL WHERE scope_key = ?",
                    GAP_SCOPE_PREFIX + value, CLAIM_SCOPE_PREFIX + value);
            if (restored == 0) {
                this.gapPoolManager.getJdbcTemplate().update("INSERT IGNORE INTO m_account_number_sequence (scope_key, next_value) VALUES (?, ?)",
                        GAP_SCOPE_PREFIX + value, value);
            }
            reclaimPools.computeIfAbsent(scopeKey, key -> new ConcurrentLinkedQueue<>()).offer(value);
        }

        private void validateOverflow(final long value) {
            if (value > MAX_NUMERIC_ACCOUNT_NUMBER) {
                throw new AccountNumberOverflowException(MAX_NUMERIC_ACCOUNT_NUMBER);
            }
        }
    }

    private static final class HiLoBlock {

        private final long blockEnd;
        private final AtomicLong current;

        private HiLoBlock(final long blockStart, final long blockEnd) {
            this.blockEnd = blockEnd;
            this.current = new AtomicLong(blockStart);
        }

        private long getAndIncrement() {
            return this.current.getAndIncrement();
        }

        private boolean hasCapacity(final long candidate) {
            return candidate <= this.blockEnd;
        }
    }
}
