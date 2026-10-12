-- Enable client account number gap reuse.

START TRANSACTION;

UPDATE c_configuration
SET enabled = 1
WHERE name = 'account-number-reuse-gaps';

COMMIT;

SELECT name, value, enabled, description
FROM c_configuration
WHERE name IN (
    'account-number-reuse-gaps',
    'account-number-sequence-block-size',
    'account-number-gap-pool-low-watermark',
    'account-number-gap-claim-timeout-minutes'
);
