-- =============================================================================
-- QueueLess PostgreSQL Initial Production Schema Migration (Flyway V1)
-- Compatible with PostgreSQL 15+ (Supabase) and H2 in PostgreSQL Mode
-- =============================================================================

-- 1. Users table (Shop owners and administrators)
CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(150) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    full_name VARCHAR(100) NOT NULL,
    phone VARCHAR(20),
    role VARCHAR(20) NOT NULL DEFAULT 'ROLE_OWNER',
    failed_pin_attempts INT DEFAULT 0,
    pin_locked_until TIMESTAMP,
    credentials_updated_at TIMESTAMP WITH TIME ZONE,
    last_login_at TIMESTAMP,
    last_login_ip VARCHAR(64),
    last_device_fingerprint VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_users_phone ON users(phone);

-- 2. Shops table (Supports duplicate shop names via unique shopId)
CREATE TABLE IF NOT EXISTS shops (
    id BIGSERIAL PRIMARY KEY,
    owner_id BIGINT NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(150) NOT NULL,
    category VARCHAR(80) NOT NULL DEFAULT 'General',
    avg_service_duration_minutes INT NOT NULL DEFAULT 15,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_shops_owner ON shops(owner_id);

-- 3. Subscriptions table (Shop entitlement tracking)
CREATE TABLE IF NOT EXISTS subscriptions (
    id BIGSERIAL PRIMARY KEY,
    shop_id BIGINT NOT NULL REFERENCES shops(id) ON DELETE CASCADE,
    plan_type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    amount NUMERIC(10, 2) NOT NULL DEFAULT 0.00,
    payment_reference VARCHAR(100),
    payment_upi_id VARCHAR(100),
    notes VARCHAR(255),
    trial_started_at TIMESTAMP,
    trial_ends_at TIMESTAMP,
    trial_consumed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_subscription_shop ON subscriptions(shop_id);
CREATE INDEX IF NOT EXISTS idx_subscription_status ON subscriptions(status);

-- 4. Trial eligibility ledger (Permanent phone fingerprinting to prevent trial abuse)
CREATE TABLE IF NOT EXISTS trial_eligibility_ledger (
    id BIGSERIAL PRIMARY KEY,
    normalized_phone VARCHAR(32) NOT NULL UNIQUE,
    phone_hmac VARCHAR(64) NOT NULL,
    trial_started_at TIMESTAMP NOT NULL,
    trial_ends_at TIMESTAMP NOT NULL,
    trial_consumed_at TIMESTAMP NOT NULL,
    owner_id BIGINT,
    shop_id BIGINT,
    owner_email VARCHAR(150),
    registration_status VARCHAR(30) NOT NULL,
    device_fingerprint VARCHAR(128),
    client_ip VARCHAR(64),
    abuse_flags VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_trial_phone_hmac ON trial_eligibility_ledger(phone_hmac);

-- 5. Queue sessions (Daily active queues with unique public joining identifiers)
CREATE TABLE IF NOT EXISTS queue_sessions (
    id BIGSERIAL PRIMARY KEY,
    shop_id BIGINT NOT NULL REFERENCES shops(id) ON DELETE CASCADE,
    session_date DATE NOT NULL,
    public_queue_id VARCHAR(32) NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    token_counter INT NOT NULL DEFAULT 0,
    started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    closed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_queue_session_shop_date ON queue_sessions(shop_id, session_date);

-- 6. Queue customers (Tokens, lifecycle statuses, and secure private tracking tokens)
CREATE TABLE IF NOT EXISTS queue_customers (
    id BIGSERIAL PRIMARY KEY,
    queue_session_id BIGINT NOT NULL REFERENCES queue_sessions(id) ON DELETE CASCADE,
    token_number VARCHAR(10) NOT NULL,
    token_sequence INT NOT NULL,
    customer_name VARCHAR(120) NOT NULL,
    customer_phone VARCHAR(20),
    tracking_token VARCHAR(64) NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL DEFAULT 'WAITING',
    added_by VARCHAR(20) NOT NULL DEFAULT 'QR_CODE',
    joined_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    called_at TIMESTAMP,
    served_at TIMESTAMP,
    completed_at TIMESTAMP,
    CONSTRAINT uq_session_token_seq UNIQUE (queue_session_id, token_sequence)
);
CREATE INDEX IF NOT EXISTS idx_customer_tracking_token ON queue_customers(tracking_token);
CREATE INDEX IF NOT EXISTS idx_customer_session_status ON queue_customers(queue_session_id, status);
CREATE INDEX IF NOT EXISTS idx_customer_session_seq ON queue_customers(queue_session_id, token_sequence);

-- 7. OTP records (Secure hashed mobile OTPs with rate-limiting and expiry)
CREATE TABLE IF NOT EXISTS otp_records (
    id BIGSERIAL PRIMARY KEY,
    normalized_phone VARCHAR(32) NOT NULL,
    otp_hash VARCHAR(128) NOT NULL,
    salt VARCHAR(32) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    resend_cooldown_until TIMESTAMP,
    verified BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_otp_phone ON otp_records(normalized_phone);

-- 8. Application settings (Persistent key-value configuration, VAPID keys, payment UPI settings)
CREATE TABLE IF NOT EXISTS application_settings (
    id BIGSERIAL PRIMARY KEY,
    setting_key VARCHAR(80) NOT NULL UNIQUE,
    setting_value VARCHAR(500) NOT NULL,
    description VARCHAR(255),
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_app_setting_key ON application_settings(setting_key);

-- 9. Business category change requests (Permanent lock enforcement and paid change requests)
CREATE TABLE IF NOT EXISTS business_category_change_requests (
    id BIGSERIAL PRIMARY KEY,
    shop_id BIGINT NOT NULL REFERENCES shops(id) ON DELETE CASCADE,
    owner_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    old_category VARCHAR(80) NOT NULL,
    requested_category VARCHAR(80) NOT NULL,
    amount NUMERIC(10, 2) NOT NULL DEFAULT 101.00,
    payment_reference VARCHAR(100),
    status VARCHAR(30) NOT NULL DEFAULT 'PAYMENT_PENDING',
    rejection_reason VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_at TIMESTAMP,
    reviewed_by VARCHAR(100)
);
CREATE INDEX IF NOT EXISTS idx_cat_req_shop ON business_category_change_requests(shop_id);
CREATE INDEX IF NOT EXISTS idx_cat_req_status ON business_category_change_requests(status);
CREATE INDEX IF NOT EXISTS idx_cat_req_utr ON business_category_change_requests(payment_reference);

-- 10. Business category change audit history
CREATE TABLE IF NOT EXISTS business_category_change_audits (
    id BIGSERIAL PRIMARY KEY,
    shop_id BIGINT NOT NULL REFERENCES shops(id) ON DELETE CASCADE,
    old_category VARCHAR(80) NOT NULL,
    new_category VARCHAR(80) NOT NULL,
    amount_paid NUMERIC(10, 2) NOT NULL,
    request_id BIGINT NOT NULL,
    approved_by VARCHAR(100) NOT NULL,
    approved_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_cat_audit_shop ON business_category_change_audits(shop_id);
CREATE INDEX IF NOT EXISTS idx_cat_audit_req ON business_category_change_audits(request_id);

-- 11. Customer Web Push subscriptions (Multi-device PWA push notifications)
CREATE TABLE IF NOT EXISTS customer_push_subscriptions (
    id BIGSERIAL PRIMARY KEY,
    customer_id BIGINT NOT NULL REFERENCES queue_customers(id) ON DELETE CASCADE,
    shop_id BIGINT NOT NULL REFERENCES shops(id) ON DELETE CASCADE,
    endpoint VARCHAR(1024) NOT NULL,
    p256dh_key VARCHAR(255) NOT NULL,
    auth_secret VARCHAR(255) NOT NULL,
    user_agent VARCHAR(512),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_push_at TIMESTAMP,
    last_push_status VARCHAR(64)
);
CREATE INDEX IF NOT EXISTS idx_push_sub_customer ON customer_push_subscriptions(customer_id, active);
CREATE INDEX IF NOT EXISTS idx_push_sub_shop ON customer_push_subscriptions(shop_id, active);
CREATE INDEX IF NOT EXISTS idx_push_sub_endpoint ON customer_push_subscriptions(endpoint);
