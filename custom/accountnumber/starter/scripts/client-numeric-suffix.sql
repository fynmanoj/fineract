-- Shared client account number numeric suffix expression.
-- Matches the Java AccountNumberGapSqlSupport.CLIENT_NUMERIC_SUFFIX_EXPR logic.
CASE
  WHEN c.account_no REGEXP '^[0-9]+$'
    THEN CAST(c.account_no AS UNSIGNED)
  WHEN c.account_no REGEXP '[0-9]+$'
    THEN CAST(REGEXP_SUBSTR(c.account_no, '[0-9]+$') AS UNSIGNED)
  ELSE NULL
END
