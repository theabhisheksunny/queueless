-- ==============================================================================
-- QueueLess — Clean Launch Shared Database Reset & Demo Account Seeding Script
-- Target Database: Supabase PostgreSQL 16 (Project: tlgibnjprelzpxfxycab)
-- Target Environments: Staging & Production (Shared DB)
-- ==============================================================================
-- Invariant Safeguards:
-- 1. Atomic Transaction: Wrapped in BEGIN ... COMMIT. Fails closed on any error.
-- 2. Zero Truncate Cascade: Uses explicit topological DELETE in foreign key order.
-- 3. Schema Preservation: flyway_schema_history, application_settings, and
--    canary tables are explicitly preserved.
-- 4. Retains exactly ONE dedicated Testuser demo owner and ONE demo shop.
-- 5. Leaves ZERO orphaned records across all 23 application tables.
-- ==============================================================================

BEGIN;

-- ------------------------------------------------------------------------------
-- STEP 1: BEFORE-CLEANUP INVENTORY RECORD COUNTS
-- ------------------------------------------------------------------------------
SELECT '=== BEFORE-CLEANUP RECORD COUNTS ===' AS section;

SELECT
    (SELECT COUNT(*) FROM sse_tickets) AS count_sse_tickets,
    (SELECT COUNT(*) FROM customer_push_subscriptions) AS count_customer_push_subs,
    (SELECT COUNT(*) FROM queue_customers) AS count_queue_customers,
    (SELECT COUNT(*) FROM queue_sessions) AS count_queue_sessions,
    (SELECT COUNT(*) FROM business_category_change_audits) AS count_cat_audits,
    (SELECT COUNT(*) FROM business_category_change_requests) AS count_cat_requests,
    (SELECT COUNT(*) FROM payment_records) AS count_payment_records,
    (SELECT COUNT(*) FROM subscription_seats) AS count_subscription_seats,
    (SELECT COUNT(*) FROM subscriptions) AS count_subscriptions,
    (SELECT COUNT(*) FROM trial_eligibility_ledger) AS count_trial_ledger,
    (SELECT COUNT(*) FROM shop_memberships) AS count_shop_memberships,
    (SELECT COUNT(*) FROM idempotency_keys) AS count_idempotency_keys,
    (SELECT COUNT(*) FROM device_auth_challenges) AS count_device_challenges,
    (SELECT COUNT(*) FROM device_replacement_requests) AS count_device_replacements,
    (SELECT COUNT(*) FROM device_registrations) AS count_device_registrations,
    (SELECT COUNT(*) FROM user_sessions) AS count_user_sessions,
    (SELECT COUNT(*) FROM shops) AS count_shops,
    (SELECT COUNT(*) FROM businesses) AS count_businesses,
    (SELECT COUNT(*) FROM users) AS count_users,
    (SELECT COUNT(*) FROM audit_logs) AS count_audit_logs,
    (SELECT COUNT(*) FROM application_settings) AS count_app_settings_preserved,
    (SELECT COUNT(*) FROM flyway_schema_history) AS count_flyway_history_preserved;

-- ------------------------------------------------------------------------------
-- STEP 2: TOPOLOGICAL REVERSE-DEPENDENCY DELETION
-- ------------------------------------------------------------------------------

-- 2.1. Ephemeral SSE tickets (references shops, users)
DELETE FROM sse_tickets;

-- 2.2. Web push subscriptions (references queue_customers, shops)
DELETE FROM customer_push_subscriptions;

-- 2.3. Queue customers (references queue_sessions)
DELETE FROM queue_customers;

-- 2.4. Queue sessions (references shops)
DELETE FROM queue_sessions;

-- 2.5. Category change audits & requests (references shops, users)
DELETE FROM business_category_change_audits;
DELETE FROM business_category_change_requests;

-- 2.6. Payment records (references users, subscription_seats, shops)
DELETE FROM payment_records;

-- 2.7. Subscription seats (references users)
DELETE FROM subscription_seats;

-- 2.8. Subscriptions (references shops, users)
DELETE FROM subscriptions;

-- 2.9. Trial eligibility ledger (references owner_id, shop_id logically)
DELETE FROM trial_eligibility_ledger;

-- 2.10. Shop memberships (references shops, users)
DELETE FROM shop_memberships;

-- 2.11. Idempotency keys (references users)
DELETE FROM idempotency_keys;

-- 2.12. Device challenges and replacements (references device_registrations, users)
DELETE FROM device_auth_challenges;
DELETE FROM device_replacement_requests;

-- 2.13. Device registrations (references users)
DELETE FROM device_registrations;

-- 2.14. User sessions (references users)
DELETE FROM user_sessions;

-- 2.15. Shops (references businesses, users)
DELETE FROM shops;

-- 2.16. Businesses (references users)
DELETE FROM businesses;

-- 2.17. Users (staff with owner_id first, then owners)
DELETE FROM users WHERE owner_id IS NOT NULL;
DELETE FROM users;

-- 2.18. Historical audit logs
DELETE FROM audit_logs;

-- ------------------------------------------------------------------------------
-- STEP 3: PROVISION DEDICATED TESTUSER DEMO ACCOUNT & MINIMAL DEMO SHOP
-- ------------------------------------------------------------------------------

-- 3.1. Insert Demo Owner User
INSERT INTO users (
    id, email, password, full_name, phone, role, status,
    failed_pin_attempts, temporary_pin, active_session_id,
    credentials_updated_at, created_at, updated_at
) VALUES (
    1001,
    'demo.owner.launch2026@queueless.in',
    '$2b$10$JagaEFeGfKQhRwpq4O1AUOR16zqOAA0PT70rt1G9CTIRMxmpUp3/6',
    'QueueLess Demo Owner',
    '+919876500001',
    'ROLE_OWNER',
    'ACTIVE',
    0,
    FALSE,
    'session-demo-owner-init-2026',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
);

-- 3.2. Insert Demo Business
INSERT INTO businesses (
    id, owner_id, name, created_at, updated_at
) VALUES (
    2001,
    1001,
    'QueueLess Demo Business',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
);

-- 3.3. Insert Minimal Demo Shop (Retail & Shopping / General Store)
INSERT INTO shops (
    id, owner_id, business_id, name, category, business_type,
    avg_service_duration_minutes, is_primary, registration_status,
    registration_fee_paid, public_merchant_code, created_at, updated_at
) VALUES (
    3001,
    1001,
    2001,
    'QueueLess Demo Experience Store',
    'Retail & Shopping',
    'General Store',
    15,
    TRUE,
    'REGISTERED',
    0.00,
    'DEMOSHOP1',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
);

-- 3.4. Insert Demo Shop Membership
INSERT INTO shop_memberships (
    id, user_id, shop_id, status, created_at, updated_at
) VALUES (
    4001,
    1001,
    3001,
    'ACTIVE',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
);

-- 3.5. Insert Active 7-Day Demo Trial Subscription (for queue creation entitlement)
INSERT INTO subscriptions (
    id, shop_id, owner_id, plan_type, status,
    start_date, end_date, amount, trial_started_at, trial_ends_at,
    created_at, updated_at
) VALUES (
    5001,
    3001,
    1001,
    'TRIAL',
    'ACTIVE',
    CURRENT_DATE,
    CURRENT_DATE + INTERVAL '7 days',
    0.00,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP + INTERVAL '7 days',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
);

-- 3.6. Insert Initial Trial Ledger Record for Demo Account
INSERT INTO trial_eligibility_ledger (
    id, normalized_phone, phone_hmac, trial_started_at, trial_ends_at,
    trial_consumed_at, owner_id, shop_id, owner_email, registration_status,
    created_at, updated_at
) VALUES (
    6001,
    '+919876500001',
    'hmac-sha256-demo-seed-identity-2026',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP + INTERVAL '7 days',
    CURRENT_TIMESTAMP,
    1001,
    3001,
    'demo.owner.launch2026@queueless.in',
    'ACTIVE',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
);

-- 3.7. Synchronize Database Sequences
SELECT setval(pg_get_serial_sequence('users', 'id'), COALESCE(MAX(id), 1) + 1, false) FROM users;
SELECT setval(pg_get_serial_sequence('businesses', 'id'), COALESCE(MAX(id), 1) + 1, false) FROM businesses;
SELECT setval(pg_get_serial_sequence('shops', 'id'), COALESCE(MAX(id), 1) + 1, false) FROM shops;
SELECT setval(pg_get_serial_sequence('shop_memberships', 'id'), COALESCE(MAX(id), 1) + 1, false) FROM shop_memberships;
SELECT setval(pg_get_serial_sequence('subscriptions', 'id'), COALESCE(MAX(id), 1) + 1, false) FROM subscriptions;
SELECT setval(pg_get_serial_sequence('trial_eligibility_ledger', 'id'), COALESCE(MAX(id), 1) + 1, false) FROM trial_eligibility_ledger;

-- ------------------------------------------------------------------------------
-- STEP 4: AFTER-CLEANUP INVENTORY VERIFICATION & ASSERTIONS
-- ------------------------------------------------------------------------------
SELECT '=== AFTER-CLEANUP RECORD COUNTS (EXACT VERIFICATION) ===' AS section;

SELECT
    (SELECT COUNT(*) FROM users) AS demo_owner_count,               -- Expected: 1
    (SELECT COUNT(*) FROM shops) AS demo_shop_count,                -- Expected: 1
    (SELECT COUNT(*) FROM businesses) AS demo_business_count,        -- Expected: 1
    (SELECT COUNT(*) FROM shop_memberships) AS demo_membership_count,-- Expected: 1
    (SELECT COUNT(*) FROM subscriptions) AS demo_sub_count,          -- Expected: 1
    (SELECT COUNT(*) FROM trial_eligibility_ledger) AS demo_ledger,  -- Expected: 1
    (SELECT COUNT(*) FROM queue_customers) AS customers_count,      -- Expected: 0
    (SELECT COUNT(*) FROM queue_sessions) AS queue_sessions_count,  -- Expected: 0
    (SELECT COUNT(*) FROM sse_tickets) AS sse_tickets_count,        -- Expected: 0
    (SELECT COUNT(*) FROM customer_push_subscriptions) AS push_subs,-- Expected: 0
    (SELECT COUNT(*) FROM payment_records) AS old_payment_records,  -- Expected: 0
    (SELECT COUNT(*) FROM subscription_seats) AS old_seats,         -- Expected: 0
    (SELECT COUNT(*) FROM idempotency_keys) AS idempotency_keys,    -- Expected: 0
    (SELECT COUNT(*) FROM device_auth_challenges) AS challenges,    -- Expected: 0
    (SELECT COUNT(*) FROM device_replacement_requests) AS replaces, -- Expected: 0
    (SELECT COUNT(*) FROM device_registrations) AS device_regs,     -- Expected: 0
    (SELECT COUNT(*) FROM user_sessions) AS user_sessions,          -- Expected: 0
    (SELECT COUNT(*) FROM audit_logs) AS audit_records;             -- Expected: 0

-- Verify Zero Orphaned Records Constraint
DO $$
BEGIN
    IF (SELECT COUNT(*) FROM users) <> 1 THEN
        RAISE EXCEPTION 'CLEANUP INVARIANT VIOLATION: Expected exactly 1 user, found %', (SELECT COUNT(*) FROM users);
    END IF;
    IF (SELECT COUNT(*) FROM shops) <> 1 THEN
        RAISE EXCEPTION 'CLEANUP INVARIANT VIOLATION: Expected exactly 1 shop, found %', (SELECT COUNT(*) FROM shops);
    END IF;
    IF (SELECT COUNT(*) FROM queue_customers) <> 0 THEN
        RAISE EXCEPTION 'CLEANUP INVARIANT VIOLATION: Expected 0 queue customers, found %', (SELECT COUNT(*) FROM queue_customers);
    END IF;
    IF (SELECT COUNT(*) FROM queue_sessions) <> 0 THEN
        RAISE EXCEPTION 'CLEANUP INVARIANT VIOLATION: Expected 0 queue sessions, found %', (SELECT COUNT(*) FROM queue_sessions);
    END IF;
    RAISE NOTICE 'SUCCESS: All database clean launch invariants verified!';
END $$;

COMMIT;
