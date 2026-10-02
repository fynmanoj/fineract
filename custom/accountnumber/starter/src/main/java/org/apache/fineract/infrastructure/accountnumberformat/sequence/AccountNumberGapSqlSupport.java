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

final class AccountNumberGapSqlSupport {

    static final String CLIENT_GLOBAL_SCOPE_KEY = "CLIENT:GLOBAL";
    static final String GAP_SCOPE_PREFIX = "CLIENT:GLOBAL:GAP:";
    static final String CLAIM_SCOPE_PREFIX = "CLIENT:GLOBAL:CLAIM:";
    static final String GAP_SCAN_CURSOR_KEY = "CLIENT:GLOBAL:GAP_SCAN";
    static final String CLUSTER_GAP_LOCK_PREFIX = "fineract:account_number_gap:";

    static final String CONFIG_REUSE_GAPS = "account-number-reuse-gaps";
    static final String CONFIG_GAP_BATCH_SIZE = "account-number-gap-pool-batch-size";
    static final String CONFIG_GAP_LOW_WATERMARK = "account-number-gap-pool-low-watermark";
    static final String CONFIG_GAP_CLAIM_TIMEOUT_MINUTES = "account-number-gap-claim-timeout-minutes";
    static final String CONFIG_STARTUP_GAP_SCAN_FILL_SIZE = "startup-gap-scan-fill-size";
    static final String CONFIG_GAP_REFILL_MAX_WINDOWS = "account-number-gap-refill-max-windows";

    static final int DEFAULT_GAP_BATCH_SIZE = 100;
    static final int DEFAULT_GAP_LOW_WATERMARK = 10;
    static final int DEFAULT_GAP_CLAIM_TIMEOUT_MINUTES = 60;
    static final int DEFAULT_STARTUP_GAP_SCAN_FILL_SIZE = 2_000;
    static final int DEFAULT_GAP_REFILL_MAX_WINDOWS = 50;
    static final long MAX_NUMERIC_ACCOUNT_NUMBER = 999_999_999L;

    static final String CLIENT_NUMERIC_SUFFIX_EXPR = """
            CASE
              WHEN c.account_no REGEXP '^[0-9]+$'
                THEN CAST(c.account_no AS UNSIGNED)
              WHEN c.account_no REGEXP '[0-9]+$'
                THEN CAST(REGEXP_SUBSTR(c.account_no, '[0-9]+$') AS UNSIGNED)
              ELSE NULL
            END
            """;

    static final String MAX_NUMERIC_SUFFIX_SQL = """
            SELECT COALESCE(MAX(%s), 0)
            FROM m_client c
            """.formatted(CLIENT_NUMERIC_SUFFIX_EXPR);

    static final String INSERT_GAP_ROWS_SQL = """
            INSERT IGNORE INTO m_account_number_sequence (scope_key, next_value)
            SELECT CONCAT('CLIENT:GLOBAL:GAP:', candidate.n), candidate.n
            FROM (
                SELECT ? + (d1.n + d2.n * 10 + d3.n * 100 + d4.n * 1000) AS n
                FROM (SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
                      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d1
                CROSS JOIN (SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
                      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d2
                CROSS JOIN (SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
                      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d3
                CROSS JOIN (SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
                      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d4
            ) candidate
            LEFT JOIN (
                SELECT %s AS n
                FROM m_client c
                WHERE %s BETWEEN ? AND ?
            ) used ON used.n = candidate.n
            WHERE candidate.n BETWEEN ? AND ?
              AND candidate.n <= ?
              AND used.n IS NULL
              AND NOT EXISTS (
                    SELECT 1 FROM m_account_number_sequence q
                    WHERE q.next_value = candidate.n
                      AND (q.scope_key LIKE 'CLIENT:GLOBAL:GAP:%%' OR q.scope_key LIKE 'CLIENT:GLOBAL:CLAIM:%%')
                )
            ORDER BY candidate.n
            LIMIT ?
            """.formatted(CLIENT_NUMERIC_SUFFIX_EXPR, CLIENT_NUMERIC_SUFFIX_EXPR);

    private AccountNumberGapSqlSupport() {}
}
