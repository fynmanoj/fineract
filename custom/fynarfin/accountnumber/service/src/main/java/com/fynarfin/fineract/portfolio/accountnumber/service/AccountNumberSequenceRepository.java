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
package com.fynarfin.fineract.portfolio.accountnumber.service;

import com.fynarfin.fineract.portfolio.accountnumber.domain.BlockRange;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class AccountNumberSequenceRepository {

    private final JdbcTemplate jdbcTemplate;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ensureScopeExists(final String scopeKey, final long initialNextValue) {
        this.jdbcTemplate.update("INSERT IGNORE INTO m_account_number_sequence (scope_key, next_value) VALUES (?, ?)", scopeKey,
                initialNextValue);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long resolveBootstrapNextValue() {
        final Long maxExisting = this.jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(CAST(account_no AS UNSIGNED)), 0) FROM m_client WHERE account_no REGEXP '^[0-9]+$'", Long.class);
        return maxExisting + 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BlockRange allocateBlock(final String scopeKey, final int blockSize) {
        this.jdbcTemplate.update("UPDATE m_account_number_sequence SET next_value = LAST_INSERT_ID(next_value + ?) WHERE scope_key = ?",
                blockSize, scopeKey);
        final Long newNextValue = this.jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        final long blockEnd = newNextValue;
        final long blockStart = newNextValue - blockSize;
        return new BlockRange(blockStart, blockEnd);
    }
}
