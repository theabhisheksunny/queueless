-- =============================================================================
-- QueueLess Flyway Migration V5: Device-Bound Authentication & Bulk Branch Registration
-- Compatible with PostgreSQL 15+ (Supabase) and H2 in PostgreSQL Mode
-- =============================================================================

-- 1. Device Registrations table (One enrolled device per user account)
CREATE TABLE IF NOT EXISTS device_registrations (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_registration_id VARCHAR(64) NOT NULL UNIQUE,
    public_key TEXT NOT NULL,
    device_name VARCHAR(120) NOT NULL,
    platform VARCHAR(60) NOT NULL,
    enrollment_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    enrolled_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_active_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMP,
    revocation_reason VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_device_reg_user ON device_registrations(user_id, enrollment_status);
CREATE INDEX IF NOT EXISTS idx_device_reg_drid ON device_registrations(device_registration_id);

-- 2. Device Replacement Requests (Owner OR authorized Admin approval required)
CREATE TABLE IF NOT EXISTS device_replacement_requests (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    old_device_id BIGINT REFERENCES device_registrations(id) ON DELETE SET NULL,
    requested_device_name VARCHAR(120) NOT NULL,
    requested_device_platform VARCHAR(60) NOT NULL,
    requested_public_key TEXT NOT NULL,
    request_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    requested_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    reviewed_by VARCHAR(100),
    approval_role VARCHAR(20),
    reviewed_at TIMESTAMP,
    rejection_reason VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_device_rep_user ON device_replacement_requests(user_id, request_status);
CREATE INDEX IF NOT EXISTS idx_device_rep_status ON device_replacement_requests(request_status);

-- 3. Extend users table for temporary PIN enforcement (First-time device enrollment)
ALTER TABLE users ADD COLUMN IF NOT EXISTS temporary_pin BOOLEAN NOT NULL DEFAULT FALSE;

-- 4. Extend payment_records table for bulk branch registration batches
ALTER TABLE payment_records ADD COLUMN IF NOT EXISTS batch_shop_ids VARCHAR(500);
ALTER TABLE payment_records ADD COLUMN IF NOT EXISTS batch_count INT NOT NULL DEFAULT 1;
