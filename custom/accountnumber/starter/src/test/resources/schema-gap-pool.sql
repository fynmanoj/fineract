CREATE TABLE m_account_number_sequence (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    scope_key VARCHAR(80) NOT NULL UNIQUE,
    next_value BIGINT NOT NULL DEFAULT 1,
    claimed_at DATETIME NULL
);

CREATE TABLE m_client (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    account_no VARCHAR(20) NOT NULL UNIQUE
);

CREATE TABLE c_configuration (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    value INT NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 0,
    description VARCHAR(500) NULL
);

INSERT INTO c_configuration (name, value, enabled, description) VALUES
('account-number-reuse-gaps', NULL, 1, 'gap reuse'),
('account-number-gap-pool-batch-size', 100, 1, 'batch'),
('account-number-gap-pool-low-watermark', 10, 1, 'watermark'),
('account-number-gap-claim-timeout-minutes', 60, 1, 'claim timeout'),
('startup-gap-scan-fill-size', 2000, 1, 'scan window'),
('account-number-gap-refill-max-windows', 50, 1, 'max windows');
