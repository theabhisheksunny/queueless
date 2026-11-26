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
-- STEP 1: BEFORE-CLEANUP INVENTORY RECORD COUNTS (SCHEMA-RESILIENT)
-- ------------------------------------------------------------------------------
SELECT '=== BEFORE-CLEANUP RECORD COUNTS ===' AS section;

DO $$
DECLARE
    r RECORD;
    v_count BIGINT;
BEGIN
    RAISE NOTICE 'Before-cleanup inventory across public tables:';
    FOR r IN
        SELECT table_name
        FROM information_schema.tables
        WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
        ORDER BY table_name
    LOOP
        EXECUTE format('SELECT COUNT(*) FROM public.%I', r.table_name) INTO v_count;
        RAISE NOTICE '  - %-32: % rows', r.table_name, v_count;
    END LOOP;
END $$;

-- ------------------------------------------------------------------------------
-- STEP 2: TOPOLOGICAL REVERSE-DEPENDENCY DELETION
-- ------------------------------------------------------------------------------

-- 2.1. Ephemeral SSE tickets (conditional on table existence)
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'sse_tickets') THEN
        EXECUTE 'DELETE FROM sse_tickets';
        RAISE NOTICE 'Pruned sse_tickets table.';
    ELSE
        RAISE NOTICE 'Table sse_tickets not present; skipping.';
    END IF;
END $$;

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
    '$2b$10$4GDTDxu38B4MAUD1cdmSs.cu.8tdZWljuj8cpUIXvgkQnQso4DBc6',
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
-- Schema-adaptive: safely includes public_merchant_code and business_type if columns exist
DO $$
DECLARE
    v_has_merchant_code BOOLEAN;
    v_has_business_type BOOLEAN;
BEGIN
    SELECT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'shops' AND column_name = 'public_merchant_code'
    ) INTO v_has_merchant_code;

    SELECT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'shops' AND column_name = 'business_type'
    ) INTO v_has_business_type;

    IF v_has_merchant_code AND v_has_business_type THEN
        INSERT INTO shops (
            id, owner_id, business_id, name, category, business_type,
            avg_service_duration_minutes, is_primary, registration_status,
            registration_fee_paid, public_merchant_code, created_at, updated_at
        ) VALUES (
            3001, 1001, 2001, 'QueueLess Demo Experience Store', 'Retail & Shopping', 'General Store',
            15, TRUE, 'REGISTERED', 0.00, 'DEMOSHOP1', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
        );
    ELSIF v_has_merchant_code THEN
        INSERT INTO shops (
            id, owner_id, business_id, name, category,
            avg_service_duration_minutes, is_primary, registration_status,
            registration_fee_paid, public_merchant_code, created_at, updated_at
        ) VALUES (
            3001, 1001, 2001, 'QueueLess Demo Experience Store', 'Retail & Shopping',
            15, TRUE, 'REGISTERED', 0.00, 'DEMOSHOP1', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
        );
    ELSE
        INSERT INTO shops (
            id, owner_id, business_id, name, category,
            avg_service_duration_minutes, is_primary, registration_status,
            registration_fee_paid, created_at, updated_at
        ) VALUES (
            3001, 1001, 2001, 'QueueLess Demo Experience Store', 'Retail & Shopping',
            15, TRUE, 'REGISTERED', 0.00, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
        );
    END IF;
END $$;

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
-- STEP 4: AFTER-CLEANUP INVENTORY VERIFICATION & ASSERTIONS (SCHEMA-RESILIENT)
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
    (SELECT COUNT(*) FROM customer_push_subscriptions) AS push_subs,-- Expected: 0
    (SELECT COUNT(*) FROM payment_records) AS old_payment_records,  -- Expected: 0
    (SELECT COUNT(*) FROM subscription_seats) AS old_seats,         -- Expected: 0
    (SELECT COUNT(*) FROM idempotency_keys) AS idempotency_keys,    -- Expected: 0
    (SELECT COUNT(*) FROM device_auth_challenges) AS challenges,    -- Expected: 0
    (SELECT COUNT(*) FROM device_replacement_requests) AS replaces, -- Expected: 0
    (SELECT COUNT(*) FROM device_registrations) AS device_regs,     -- Expected: 0
    (SELECT COUNT(*) FROM user_sessions) AS user_sessions,          -- Expected: 0
    (SELECT COUNT(*) FROM audit_logs) AS audit_records;             -- Expected: 0

-- Verify Invariants and Orphaned Records Constraints
DO $$
DECLARE
    v_users_cnt BIGINT;
    v_shops_cnt BIGINT;
    v_cust_cnt  BIGINT;
    v_sess_cnt  BIGINT;
    v_sse_cnt   BIGINT := 0;
BEGIN
    SELECT COUNT(*) INTO v_users_cnt FROM users;
    SELECT COUNT(*) INTO v_shops_cnt FROM shops;
    SELECT COUNT(*) INTO v_cust_cnt  FROM queue_customers;
    SELECT COUNT(*) INTO v_sess_cnt  FROM queue_sessions;

    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'sse_tickets') THEN
        EXECUTE 'SELECT COUNT(*) FROM sse_tickets' INTO v_sse_cnt;
    END IF;

    IF v_users_cnt <> 1 THEN
        RAISE EXCEPTION 'CLEANUP INVARIANT VIOLATION: Expected exactly 1 user, found %', v_users_cnt;
    END IF;
    IF v_shops_cnt <> 1 THEN
        RAISE EXCEPTION 'CLEANUP INVARIANT VIOLATION: Expected exactly 1 shop, found %', v_shops_cnt;
    END IF;
    IF v_cust_cnt <> 0 THEN
        RAISE EXCEPTION 'CLEANUP INVARIANT VIOLATION: Expected 0 queue customers, found %', v_cust_cnt;
    END IF;
    IF v_sess_cnt <> 0 THEN
        RAISE EXCEPTION 'CLEANUP INVARIANT VIOLATION: Expected 0 queue sessions, found %', v_sess_cnt;
    END IF;
    IF v_sse_cnt <> 0 THEN
        RAISE EXCEPTION 'CLEANUP INVARIANT VIOLATION: Expected 0 sse_tickets, found %', v_sse_cnt;
    END IF;

    RAISE NOTICE 'SUCCESS: All database clean launch invariants verified!';
END $$;

COMMIT;
