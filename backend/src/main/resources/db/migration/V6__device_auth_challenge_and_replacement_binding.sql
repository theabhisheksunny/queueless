-- =============================================================================
-- QueueLess Flyway Migration V6: Device Authentication Challenges & Replacement Binding
-- Compatible with PostgreSQL 15+ (Supabase) and H2 in PostgreSQL Mode
-- =============================================================================

-- 1. Device Authentication Challenges (Cryptographic Challenge-Response Replay Protection)
CREATE TABLE IF NOT EXISTS device_auth_challenges (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_registration_id VARCHAR(100) NOT NULL,
    challenge VARCHAR(128) NOT NULL UNIQUE,
    purpose VARCHAR(30) NOT NULL DEFAULT 'DEVICE_LOGIN',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    consumed_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_challenge_str ON device_auth_challenges(challenge);
CREATE INDEX IF NOT EXISTS idx_challenge_user_status ON device_auth_challenges(user_id, status);

-- 2. Bind exact Device Registration ID to Device Replacement Requests
ALTER TABLE device_replacement_requests ADD COLUMN IF NOT EXISTS requested_device_registration_id VARCHAR(100);
CREATE INDEX IF NOT EXISTS idx_device_rep_drid ON device_replacement_requests(requested_device_registration_id);
