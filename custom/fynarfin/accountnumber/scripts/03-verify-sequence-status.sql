-- Check client account numbering strategy and sequence state.

SELECT
    anf.account_type_enum,
    anf.numbering_strategy_enum,
    CASE anf.numbering_strategy_enum
        WHEN 1 THEN 'ID_BASED'
        WHEN 2 THEN 'SEQUENCE_TABLE'
        ELSE 'UNKNOWN'
    END AS strategy_name,
    anf.prefix_type_enum,
    anf.prefix_character
FROM c_account_number_format anf
WHERE anf.account_type_enum = 1;

SELECT scope_key, next_value
FROM m_account_number_sequence
WHERE scope_key IN ('CLIENT:GLOBAL', 'CLIENT:GLOBAL:GAP_SCAN');

SELECT
    COUNT(*) AS client_count,
    MAX(
        CASE
          WHEN c.account_no REGEXP '^[0-9]+$'
            THEN CAST(c.account_no AS UNSIGNED)
          WHEN c.account_no REGEXP '[0-9]+$'
            THEN CAST(REGEXP_SUBSTR(c.account_no, '[0-9]+$') AS UNSIGNED)
          ELSE NULL
        END
    ) AS max_numeric_suffix
FROM m_client c;

SELECT COUNT(*) AS gap_count
FROM m_account_number_sequence
WHERE scope_key LIKE 'CLIENT:GLOBAL:GAP:%';

SELECT scope_key, claimed_at
FROM m_account_number_sequence
WHERE scope_key LIKE 'CLIENT:GLOBAL:CLAIM:%';

SELECT name, value, enabled, description
FROM c_configuration
WHERE name IN (
    'account-number-sequence-block-size',
    'account-number-reuse-gaps',
    'account-number-gap-pool-batch-size',
    'account-number-gap-pool-low-watermark',
    'account-number-gap-claim-timeout-minutes'
);
