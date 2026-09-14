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

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.fineract.portfolio.client.exception.AccountNumberOverflowException;
import org.apache.fineract.portfolio.client.service.AccountNumberSequenceService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfiguration
public class AccountNumberSequenceAutoConfiguration {

    private static final String CLIENT_GLOBAL_SCOPE_KEY = "CLIENT:GLOBAL";
    private static final long MAX_NUMERIC_ACCOUNT_NUMBER = 999_999_999L;

    @Bean
    @Primary
    public AccountNumberSequenceService hiLoAccountNumberSequenceService(final JdbcTemplate jdbcTemplate,
            final PlatformTransactionManager transactionManager) {
        return new HiLoAccountNumberSequenceService(jdbcTemplate, transactionManager);
    }

    static final class HiLoAccountNumberSequenceService implements AccountNumberSequenceService {

        private final JdbcTemplate jdbcTemplate;
        private final TransactionTemplate requiresNewTemplate;
        private final Map<String, HiLoBlock> blocks = new ConcurrentHashMap<>();
        private final Map<String, Queue<Long>> reclaimPools = new ConcurrentHashMap<>();
        private final Map<String, ReentrantLock> refillLocks = new ConcurrentHashMap<>();
        private final Map<String, Boolean> bootstrappedScopes = new ConcurrentHashMap<>();

        HiLoAccountNumberSequenceService(final JdbcTemplate jdbcTemplate, final PlatformTransactionManager transactionManager) {
            this.jdbcTemplate = jdbcTemplate;
            this.requiresNewTemplate = new TransactionTemplate(transactionManager);
            this.requiresNewTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        }

        @Override
        public long nextValue(final int blockSize) {
            if (blockSize <= 0) {
                throw new IllegalArgumentException("blockSize must be positive");
            }
            ensureBootstrapped(CLIENT_GLOBAL_SCOPE_KEY);

            final Long reclaimed = pollReclaim(CLIENT_GLOBAL_SCOPE_KEY);
            if (reclaimed != null) {
                return registerReclaimOnRollback(CLIENT_GLOBAL_SCOPE_KEY, reclaimed);
            }

            HiLoBlock block = this.blocks.get(CLIENT_GLOBAL_SCOPE_KEY);
            if (block != null) {
                final long candidate = block.getAndIncrement();
                if (block.hasCapacity(candidate)) {
                    validateOverflow(candidate);
                    return registerReclaimOnRollback(CLIENT_GLOBAL_SCOPE_KEY, candidate);
                }
            }

            return registerReclaimOnRollback(CLIENT_GLOBAL_SCOPE_KEY, allocateFromRefill(blockSize));
        }

        private long allocateFromRefill(final int blockSize) {
            final ReentrantLock lock = this.refillLocks.computeIfAbsent(CLIENT_GLOBAL_SCOPE_KEY, key -> new ReentrantLock());
            lock.lock();
            try {
                HiLoBlock block = this.blocks.get(CLIENT_GLOBAL_SCOPE_KEY);
                if (block != null) {
                    final long candidate = block.getAndIncrement();
                    if (block.hasCapacity(candidate)) {
                        validateOverflow(candidate);
                        return candidate;
                    }
                }
                final long blockStart = allocateBlock(blockSize);
                final long blockEnd = blockStart + blockSize - 1;
                validateOverflow(blockEnd);
                block = new HiLoBlock(blockStart, blockEnd);
                this.blocks.put(CLIENT_GLOBAL_SCOPE_KEY, block);
                final long first = block.getAndIncrement();
                validateOverflow(first);
                return first;
            } finally {
                lock.unlock();
            }
        }

        private void ensureBootstrapped(final String scopeKey) {
            this.bootstrappedScopes.computeIfAbsent(scopeKey, key -> {
                final Long maxExisting = this.jdbcTemplate.queryForObject(
                        "SELECT COALESCE(MAX(CAST(account_no AS UNSIGNED)), 0) FROM m_client WHERE account_no REGEXP '^[0-9]+$'",
                        Long.class);
                this.jdbcTemplate.update("INSERT IGNORE INTO m_account_number_sequence (scope_key, next_value) VALUES (?, ?)", scopeKey,
                        maxExisting + 1);
                return Boolean.TRUE;
            });
        }

        private long allocateBlock(final int blockSize) {
            return this.requiresNewTemplate.execute(status -> {
                this.jdbcTemplate.update("UPDATE m_account_number_sequence SET next_value = LAST_INSERT_ID(next_value + ?) WHERE scope_key = ?",
                        blockSize, CLIENT_GLOBAL_SCOPE_KEY);
                final Long newNextValue = this.jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
                return newNextValue - blockSize + 1;
            });
        }

        private Long pollReclaim(final String scopeKey) {
            final Queue<Long> pool = this.reclaimPools.get(scopeKey);
            return pool == null ? null : pool.poll();
        }

        private long registerReclaimOnRollback(final String scopeKey, final long value) {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

                    @Override
                    public void afterCompletion(final int status) {
                        if (status == STATUS_ROLLED_BACK) {
                            reclaimPools.computeIfAbsent(scopeKey, key -> new ConcurrentLinkedQueue<>()).offer(value);
                        }
                    }
                });
            }
            return value;
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
