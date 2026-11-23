-- =============================================================================
-- QueueLess Flyway Migration V8: Scalability Indexes and Dedicated Idempotency Table
-- Compatible with PostgreSQL 15+ (Supabase) and H2 in PostgreSQL Mode
-- =============================================================================

-- 1. Performance Indexes for Query Optimization and Sorting
CREATE INDEX IF NOT EXISTS idx_audit_logs_created_at ON audit_logs(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_payment_rec_created_at ON payment_records(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_payment_rec_status_created ON payment_records(status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_queue_customers_completed_at ON queue_customers(completed_at);
CREATE INDEX IF NOT EXISTS idx_queue_cust_session_status_seq ON queue_customers(queue_session_id, status, token_sequence);
CREATE INDEX IF NOT EXISTS idx_sub_owner_end_date ON subscriptions(owner_id, end_date DESC);
CREATE INDEX IF NOT EXISTS idx_shops_owner_created ON shops(owner_id, created_at ASC);
CREATE INDEX IF NOT EXISTS idx_challenge_expires ON device_auth_challenges(expires_at);

-- 2. Dedicated Idempotency Keys Table (Segregates operational cache from privileged audit trail)
CREATE TABLE IF NOT EXISTS idempotency_keys (
    id BIGSERIAL PRIMARY KEY,
    idempotency_key VARCHAR(128) NOT NULL,
    owner_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    scope VARCHAR(50) NOT NULL DEFAULT 'BRANCH_CREATION',
    request_hash VARCHAR(64) NOT NULL,
    response_payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'COMMITTED',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_idempotency_owner_scope_key UNIQUE (owner_id, scope, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_idemp_expires ON idempotency_keys(expires_at);
CREATE INDEX IF NOT EXISTS idx_idemp_lookup ON idempotency_keys(owner_id, scope, idempotency_key);
