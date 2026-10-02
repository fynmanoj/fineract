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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class AccountNumberGapPoolIntegrationTest {

    @Container
    private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.2");

    private JdbcTemplate jdbcTemplate;
    private AccountNumberGapPoolManager gapPoolManager;
    private AccountNumberSequenceAutoConfiguration.HiLoAccountNumberSequenceService sequenceService;

    @BeforeEach
    void setUp() throws Exception {
        final DataSource dataSource = new DriverManagerDataSource(MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
        try (final var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema-gap-pool.sql"));
        }
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        final PlatformTransactionManager transactionManager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource);
        this.gapPoolManager = new AccountNumberGapPoolManager(this.jdbcTemplate, transactionManager, Runnable::run);
        this.sequenceService = new AccountNumberSequenceAutoConfiguration.HiLoAccountNumberSequenceService(this.gapPoolManager,
                transactionManager);
    }

    @Test
    void refillGapPool_findsGapAtTenThousandChunkBoundary() {
        insertClient("9999");
        insertClient("10001");
        this.jdbcTemplate.update("UPDATE c_configuration SET value = 10000 WHERE name = 'startup-gap-scan-fill-size'");
        this.jdbcTemplate.update("UPDATE c_configuration SET value = 1 WHERE name = 'account-number-gap-pool-low-watermark'");
        this.jdbcTemplate.update(
                "INSERT INTO m_account_number_sequence (scope_key, next_value) VALUES ('CLIENT:GLOBAL:GAP_SCAN', 1) "
                        + "ON DUPLICATE KEY UPDATE next_value = 1");

        this.gapPoolManager.refillGapPoolInternal();

        final Integer gapTenThousand = this.jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM m_account_number_sequence WHERE scope_key = 'CLIENT:GLOBAL:GAP:10000'", Integer.class);
        assertEquals(1, gapTenThousand);
    }

    @Test
    void refillGapPool_findsGapAtTwentyThousandWhenCursorStartsAtNextChunk() {
        insertClient("19999");
        insertClient("20001");
        this.jdbcTemplate.update("UPDATE c_configuration SET value = 10000 WHERE name = 'startup-gap-scan-fill-size'");
        this.jdbcTemplate.update("UPDATE c_configuration SET value = 1 WHERE name = 'account-number-gap-pool-low-watermark'");
        this.jdbcTemplate.update(
                "INSERT INTO m_account_number_sequence (scope_key, next_value) VALUES ('CLIENT:GLOBAL:GAP_SCAN', 10001) "
                        + "ON DUPLICATE KEY UPDATE next_value = 10001");

        this.gapPoolManager.refillGapPoolInternal();

        final Integer gapTwentyThousand = this.jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM m_account_number_sequence WHERE scope_key = 'CLIENT:GLOBAL:GAP:20000'", Integer.class);
        assertEquals(1, gapTwentyThousand);
    }

    @Test
    void refillGapPool_findsMissingSuffixesForPrefixedAccountNumbers() {
        insertClient("SS000000001");
        insertClient("SS000000003");

        this.gapPoolManager.refillGapPoolInternal();

        final Integer gapTwo = this.jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM m_account_number_sequence WHERE scope_key = 'CLIENT:GLOBAL:GAP:2'", Integer.class);
        assertEquals(1, gapTwo);
    }

    @Test
    void gapClaim_commit_deletesClaimRow() {
        this.jdbcTemplate.update("INSERT INTO m_account_number_sequence (scope_key, next_value) VALUES ('CLIENT:GLOBAL:GAP:5', 5)");
        final TransactionTemplate transactionTemplate = new TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(this.jdbcTemplate.getDataSource()));

        final Long allocated = transactionTemplate.execute(status -> this.sequenceService.nextValue(10));

        assertEquals(5L, allocated);
        final Integer claimCount = this.jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM m_account_number_sequence WHERE scope_key = 'CLIENT:GLOBAL:CLAIM:5'", Integer.class);
        assertEquals(0, claimCount);
    }

    @Test
    void gapClaim_rollback_restoresGapRow() {
        this.jdbcTemplate.update("INSERT INTO m_account_number_sequence (scope_key, next_value) VALUES ('CLIENT:GLOBAL:GAP:5', 5)");
        final TransactionTemplate transactionTemplate = new TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(this.jdbcTemplate.getDataSource()));

        transactionTemplate.execute(status -> {
            assertEquals(5L, this.sequenceService.nextValue(10));
            status.setRollbackOnly();
            return null;
        });

        final String scopeKey = this.jdbcTemplate.queryForObject(
                "SELECT scope_key FROM m_account_number_sequence WHERE next_value = 5", String.class);
        assertEquals("CLIENT:GLOBAL:GAP:5", scopeKey);
    }

    @Test
    void reclaimOrphanedClaims_restoresExpiredClaimRows() {
        this.jdbcTemplate.update(
                "INSERT INTO m_account_number_sequence (scope_key, next_value, claimed_at) VALUES ('CLIENT:GLOBAL:CLAIM:9', 9, NOW() - INTERVAL 2 HOUR)");
        this.jdbcTemplate.update("UPDATE c_configuration SET value = 30 WHERE name = 'account-number-gap-claim-timeout-minutes'");

        this.gapPoolManager.reclaimOrphanedClaims();

        final String scopeKey = this.jdbcTemplate.queryForObject(
                "SELECT scope_key FROM m_account_number_sequence WHERE next_value = 9", String.class);
        assertEquals("CLIENT:GLOBAL:GAP:9", scopeKey);
    }

    @Test
    void nextValue_withoutTransaction_deletesClaimImmediately() {
        this.jdbcTemplate.update("INSERT INTO m_account_number_sequence (scope_key, next_value) VALUES ('CLIENT:GLOBAL:GAP:7', 7)");

        final long allocated = this.sequenceService.nextValue(10);

        assertEquals(7L, allocated);
        final Integer claimCount = this.jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM m_account_number_sequence WHERE scope_key = 'CLIENT:GLOBAL:CLAIM:7'", Integer.class);
        assertEquals(0, claimCount);
    }

    @Test
    void concurrentNextValue_allocatesDistinctGapRows() throws Exception {
        IntStream.rangeClosed(1, 20).forEach(i -> this.jdbcTemplate.update(
                "INSERT INTO m_account_number_sequence (scope_key, next_value) VALUES (?, ?)", "CLIENT:GLOBAL:GAP:" + i, i));

        final ExecutorService executor = Executors.newFixedThreadPool(8);
        final Set<Long> allocated = ConcurrentHashMap.newKeySet();
        try {
            final Future<?>[] futures = IntStream.range(0, 20).mapToObj(
                    i -> executor.submit(() -> allocated.add(this.sequenceService.nextValue(10)))).toArray(Future[]::new);
            for (final Future<?> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        assertEquals(20, allocated.size());
        assertTrue(allocated.contains(1L));
        assertTrue(allocated.contains(20L));
    }

    private void insertClient(final String accountNo) {
        this.jdbcTemplate.update("INSERT INTO m_client (account_no) VALUES (?)", accountNo);
    }
}
