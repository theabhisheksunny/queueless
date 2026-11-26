-- =========================================================================
-- QueueLess Database Migration: V9__create_sse_tickets.sql
-- Objective: Distributed Shared Atomic Single-Use SSE Ticket Store
-- Execution: Discovered in classpath:db/migration. Applied to Supabase ONLY upon approval.
-- =========================================================================

CREATE TABLE IF NOT EXISTS sse_tickets (
    ticket VARCHAR(64) PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    shop_id BIGINT NOT NULL REFERENCES shops(id) ON DELETE CASCADE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_sse_tickets_expires_at ON sse_tickets(expires_at);
CREATE INDEX IF NOT EXISTS idx_sse_tickets_shop_user ON sse_tickets(shop_id, user_id);
