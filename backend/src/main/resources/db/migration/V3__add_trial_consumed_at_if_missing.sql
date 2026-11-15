-- Flyway Migration V3: Ensure trial_consumed_at column exists in subscriptions table
-- Guarantees compatibility for existing local and staging databases created with earlier schema versions.

ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS trial_consumed_at TIMESTAMP;
