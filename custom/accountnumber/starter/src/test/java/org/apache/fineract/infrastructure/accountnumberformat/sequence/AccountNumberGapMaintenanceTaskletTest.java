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
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

@ExtendWith(MockitoExtension.class)
class AccountNumberGapMaintenanceTaskletTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private StepContribution stepContribution;

    @Mock
    private ChunkContext chunkContext;

    private AccountNumberGapPoolManager gapPoolManager;
    private AccountNumberGapMaintenanceTasklet tasklet;

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
        this.tasklet = new AccountNumberGapMaintenanceTasklet(this.gapPoolManager);
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default", "Asia/Kolkata", null));
        when(this.jdbcTemplate.queryForObject(org.mockito.ArgumentMatchers.eq("SELECT enabled FROM c_configuration WHERE name = ?"),
                org.mockito.ArgumentMatchers.eq(Boolean.class), org.mockito.ArgumentMatchers.eq("account-number-reuse-gaps"))).thenReturn(false);
        when(this.jdbcTemplate.queryForObject(org.mockito.ArgumentMatchers.eq("SELECT COUNT(*) FROM m_account_number_sequence WHERE scope_key LIKE ?"),
                org.mockito.ArgumentMatchers.eq(Integer.class), org.mockito.ArgumentMatchers.eq("CLIENT:GLOBAL:GAP:%"))).thenReturn(3);
        when(this.jdbcTemplate.queryForObject(org.mockito.ArgumentMatchers.eq("SELECT value FROM c_configuration WHERE name = ?"),
                org.mockito.ArgumentMatchers.eq(Integer.class), org.mockito.ArgumentMatchers.eq("account-number-gap-pool-low-watermark")))
                .thenReturn(10);
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    void execute_invokesClusterSafeGapMaintenance() throws Exception {
        final RepeatStatus status = this.tasklet.execute(this.stepContribution, this.chunkContext);

        org.junit.jupiter.api.Assertions.assertEquals(RepeatStatus.FINISHED, status);
        verify(this.jdbcTemplate).queryForObject(org.mockito.ArgumentMatchers.eq("SELECT enabled FROM c_configuration WHERE name = ?"),
                org.mockito.ArgumentMatchers.eq(Boolean.class), org.mockito.ArgumentMatchers.eq("account-number-reuse-gaps"));
    }
}
