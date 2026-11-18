-- local profile only (Flyway location classpath:db/local): which scenario + fingerprint seeded each application.
CREATE TABLE seed_scenarios (
    loan_application_id VARCHAR(64) PRIMARY KEY REFERENCES loan_applications ON DELETE CASCADE,
    scenario_id VARCHAR(20) NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    seeded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
