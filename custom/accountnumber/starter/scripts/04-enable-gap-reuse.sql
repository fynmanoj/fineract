-- Enable client account number gap reuse.

START TRANSACTION;

UPDATE c_configuration
SET enabled = 1
WHERE name = 'account-number-reuse-gaps';

COMMIT;

SELECT name, enabled, description
FROM c_configuration
WHERE name IN (
    'account-number-reuse-gaps',
    'account-number-gap-pool-batch-size',
    'account-number-gap-pool-low-watermark',
    'account-number-gap-claim-timeout-minutes',
    'startup-gap-scan-fill-size',
    'account-number-gap-refill-max-windows'
);
