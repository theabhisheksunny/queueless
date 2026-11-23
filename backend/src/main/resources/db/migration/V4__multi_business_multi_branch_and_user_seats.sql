-- =============================================================================
-- QueueLess Flyway Migration V4: Multi-Business, Multi-Branch & User Seats
-- Compatible with PostgreSQL 15+ (Supabase) and H2 in PostgreSQL Mode
-- =============================================================================

-- 1. Businesses table
CREATE TABLE IF NOT EXISTS businesses (
    id BIGSERIAL PRIMARY KEY,
    owner_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(150) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_businesses_owner ON businesses(owner_id);

-- 2. Drop unique owner constraint on shops to allow multi-branch ownership
ALTER TABLE shops DROP CONSTRAINT IF EXISTS shops_owner_id_key;

-- 3. Extend shops with business association and branch fields
ALTER TABLE shops ADD COLUMN IF NOT EXISTS business_id BIGINT REFERENCES businesses(id) ON DELETE SET NULL;
ALTER TABLE shops ADD COLUMN IF NOT EXISTS branch_name VARCHAR(150);
ALTER TABLE shops ADD COLUMN IF NOT EXISTS address VARCHAR(255);
ALTER TABLE shops ADD COLUMN IF NOT EXISTS phone VARCHAR(20);
ALTER TABLE shops ADD COLUMN IF NOT EXISTS is_primary BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE shops ADD COLUMN IF NOT EXISTS registration_status VARCHAR(30) NOT NULL DEFAULT 'REGISTERED';
ALTER TABLE shops ADD COLUMN IF NOT EXISTS registration_fee_paid NUMERIC(10, 2) NOT NULL DEFAULT 0.00;
CREATE INDEX IF NOT EXISTS idx_shops_business ON shops(business_id);

-- 4. Backfill businesses for existing shops
INSERT INTO businesses (owner_id, name, created_at, updated_at)
SELECT s.owner_id, s.name, s.created_at, s.updated_at
FROM shops s
WHERE NOT EXISTS (SELECT 1 FROM businesses b WHERE b.owner_id = s.owner_id);

-- 5. Link existing shops to their business and mark as primary
UPDATE shops s
SET business_id = (SELECT b.id FROM businesses b WHERE b.owner_id = s.owner_id LIMIT 1),
    is_primary = TRUE,
    registration_status = 'REGISTERED'
WHERE s.business_id IS NULL;

-- 6. Extend users for staff accounts, active sessions, and status
ALTER TABLE users ADD COLUMN IF NOT EXISTS owner_id BIGINT REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE users ADD COLUMN IF NOT EXISTS active_session_id VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_users_owner ON users(owner_id);

-- 7. Shop memberships (Mapping staff users to authorized branches)
CREATE TABLE IF NOT EXISTS shop_memberships (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    shop_id BIGINT NOT NULL REFERENCES shops(id) ON DELETE CASCADE,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_user_shop UNIQUE (user_id, shop_id)
);
CREATE INDEX IF NOT EXISTS idx_shop_memberships_user ON shop_memberships(user_id);
CREATE INDEX IF NOT EXISTS idx_shop_memberships_shop ON shop_memberships(shop_id);

-- 8. Subscription seats (Additional user seats purchased by owner)
CREATE TABLE IF NOT EXISTS subscription_seats (
    id BIGSERIAL PRIMARY KEY,
    owner_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    plan_type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    amount NUMERIC(10, 2) NOT NULL DEFAULT 0.00,
    assigned_user_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    payment_reference VARCHAR(100),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_subscription_seats_owner ON subscription_seats(owner_id);
CREATE INDEX IF NOT EXISTS idx_subscription_seats_assigned ON subscription_seats(assigned_user_id);

-- 9. Extend subscriptions with owner_id and backfill
ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS owner_id BIGINT REFERENCES users(id) ON DELETE CASCADE;
CREATE INDEX IF NOT EXISTS idx_subscriptions_owner ON subscriptions(owner_id);

UPDATE subscriptions sub
SET owner_id = (SELECT s.owner_id FROM shops s WHERE s.id = sub.shop_id)
WHERE sub.owner_id IS NULL;

-- 10. Unified payment records ledger
CREATE TABLE IF NOT EXISTS payment_records (
    id BIGSERIAL PRIMARY KEY,
    payment_type VARCHAR(40) NOT NULL,
    owner_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    shop_id BIGINT REFERENCES shops(id) ON DELETE SET NULL,
    seat_id BIGINT REFERENCES subscription_seats(id) ON DELETE SET NULL,
    amount NUMERIC(10, 2) NOT NULL,
    payment_reference VARCHAR(100) NOT NULL,
    payment_upi_id VARCHAR(100) NOT NULL DEFAULT 'absunny@ybl',
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING_APPROVAL',
    plan_type VARCHAR(20),
    notes VARCHAR(255),
    rejection_reason VARCHAR(255),
    reviewed_by VARCHAR(100),
    reviewed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_payment_rec_ref ON payment_records(payment_reference);
CREATE INDEX IF NOT EXISTS idx_payment_rec_owner ON payment_records(owner_id, status);

-- 11. User device and session management
CREATE TABLE IF NOT EXISTS user_sessions (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    session_id VARCHAR(64) NOT NULL UNIQUE,
    device_fingerprint VARCHAR(255),
    client_ip VARCHAR(64),
    user_agent VARCHAR(512),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    last_active_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_user_sessions_user ON user_sessions(user_id, status);
CREATE INDEX IF NOT EXISTS idx_user_sessions_sid ON user_sessions(session_id);

-- 12. Administrative audit logging
CREATE TABLE IF NOT EXISTS audit_logs (
    id BIGSERIAL PRIMARY KEY,
    action VARCHAR(80) NOT NULL,
    actor VARCHAR(100) NOT NULL,
    target_type VARCHAR(50) NOT NULL,
    target_id VARCHAR(100) NOT NULL,
    details VARCHAR(500),
    client_ip VARCHAR(64),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_audit_actor ON audit_logs(actor);
CREATE INDEX IF NOT EXISTS idx_audit_action ON audit_logs(action);
CREATE INDEX IF NOT EXISTS idx_audit_target ON audit_logs(target_type, target_id);
