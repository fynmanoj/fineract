-- Enable SEQUENCE_TABLE client account numbers on a running tenant database.
-- Safe to re-run: uses GREATEST to never lower next_value.
-- CLIENT entity type enum = 1, SEQUENCE_TABLE strategy = 2, ID_BASED = 1

START TRANSACTION;

INSERT INTO c_account_number_format (account_type_enum, prefix_type_enum, prefix_character, numbering_strategy_enum)
SELECT 1, NULL, NULL, 1
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM c_account_number_format WHERE account_type_enum = 1);

UPDATE c_account_number_format
SET numbering_strategy_enum = 2
WHERE account_type_enum = 1;

INSERT INTO m_account_number_sequence (scope_key, next_value)
SELECT 'CLIENT:GLOBAL',
       COALESCE(
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
       ) + 1
ON DUPLICATE KEY UPDATE
    next_value = GREATEST(
        next_value,
        COALESCE(
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
        ) + 1
    );

COMMIT;

SELECT account_type_enum, numbering_strategy_enum
FROM c_account_number_format
WHERE account_type_enum = 1;

SELECT scope_key, next_value
FROM m_account_number_sequence
WHERE scope_key = 'CLIENT:GLOBAL';
