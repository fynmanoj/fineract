-- Manual gap pool prefill. Repeat until gaps_inserted_this_run = 0.
-- Uses the same windowed discovery approach as the application.

SET @batch_size = COALESCE(
    (SELECT value FROM c_configuration WHERE name = 'account-number-gap-pool-batch-size'),
    100
);
SET @max_suffix = COALESCE(
    (SELECT MAX(
        CASE
          WHEN c.account_no REGEXP '^[0-9]+$'
            THEN CAST(c.account_no AS UNSIGNED)
          WHEN c.account_no REGEXP '[0-9]+$'
            THEN CAST(REGEXP_SUBSTR(c.account_no, '[0-9]+$') AS UNSIGNED)
          ELSE NULL
        END
    )
     FROM m_client c),
    0
);
SET @window_size = COALESCE(
    (SELECT value FROM c_configuration WHERE name = 'startup-gap-scan-fill-size'),
    2000
);
SET @cursor = COALESCE(
    (SELECT next_value FROM m_account_number_sequence WHERE scope_key = 'CLIENT:GLOBAL:GAP_SCAN'),
    1
);
SET @scan_end = LEAST(@cursor + @window_size - 1, @max_suffix);

INSERT IGNORE INTO m_account_number_sequence (scope_key, next_value)
SELECT CONCAT('CLIENT:GLOBAL:GAP:', candidate.n), candidate.n
FROM (
    SELECT @cursor + (d1.n + d2.n * 10 + d3.n * 100 + d4.n * 1000) AS n
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
    SELECT CASE
             WHEN c.account_no REGEXP '^[0-9]+$'
               THEN CAST(c.account_no AS UNSIGNED)
             WHEN c.account_no REGEXP '[0-9]+$'
               THEN CAST(REGEXP_SUBSTR(c.account_no, '[0-9]+$') AS UNSIGNED)
             ELSE NULL
           END AS n
    FROM m_client c
    WHERE CASE
            WHEN c.account_no REGEXP '^[0-9]+$'
              THEN CAST(c.account_no AS UNSIGNED)
            WHEN c.account_no REGEXP '[0-9]+$'
              THEN CAST(REGEXP_SUBSTR(c.account_no, '[0-9]+$') AS UNSIGNED)
            ELSE NULL
          END BETWEEN @cursor AND @scan_end
) used ON used.n = candidate.n
WHERE candidate.n BETWEEN @cursor AND @scan_end
  AND candidate.n <= @max_suffix
  AND used.n IS NULL
  AND NOT EXISTS (
        SELECT 1 FROM m_account_number_sequence q
        WHERE q.next_value = candidate.n
          AND (q.scope_key LIKE 'CLIENT:GLOBAL:GAP:%' OR q.scope_key LIKE 'CLIENT:GLOBAL:CLAIM:%')
    )
ORDER BY candidate.n
LIMIT @batch_size;

SELECT ROW_COUNT() AS gaps_inserted_this_run;

INSERT INTO m_account_number_sequence (scope_key, next_value)
VALUES ('CLIENT:GLOBAL:GAP_SCAN', IF(@scan_end >= @max_suffix, 1, @scan_end + 1))
ON DUPLICATE KEY UPDATE next_value = IF(@scan_end >= @max_suffix, 1, @scan_end + 1);

SELECT COUNT(*) AS gap_count
FROM m_account_number_sequence
WHERE scope_key LIKE 'CLIENT:GLOBAL:GAP:%';
