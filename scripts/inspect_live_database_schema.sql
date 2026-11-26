-- ==============================================================================
-- QueueLess — Read-Only Live Database Schema & State Inspection Script
-- Target Database: Supabase PostgreSQL 16 (Project: tlgibnjprelzpxfxycab)
-- Mode: STRICTLY READ-ONLY (Zero modifications, zero data changes)
-- ==============================================================================

-- ------------------------------------------------------------------------------
-- QUERY 1: Database & Session Context
-- ------------------------------------------------------------------------------
SELECT 
    current_database() AS database_name,
    current_schema() AS active_schema,
    current_user AS session_user,
    version() AS postgres_version;

-- ------------------------------------------------------------------------------
-- QUERY 2: Flyway Migration History (Authoritative Schema Version Baseline)
-- Answers: Whether V9, V10, V11 exist or were successfully applied.
-- ------------------------------------------------------------------------------
SELECT 
    installed_rank,
    version,
    description,
    type,
    script,
    installed_by,
    installed_on,
    execution_time,
    success
FROM flyway_schema_history
ORDER BY installed_rank ASC;

-- ------------------------------------------------------------------------------
-- QUERY 3: Search for 'sse_tickets' or Similar Names Across ALL Schemas
-- Answers: Does sse_tickets exist in another schema or under a different name?
-- ------------------------------------------------------------------------------
SELECT 
    table_schema,
    table_name,
    table_type
FROM information_schema.tables
WHERE table_name ILIKE '%ticket%' 
   OR table_name ILIKE '%sse%'
ORDER BY table_schema, table_name;

-- ------------------------------------------------------------------------------
-- QUERY 4: Complete 23-Table Inventory & Existence Status in Schema 'public'
-- ------------------------------------------------------------------------------
SELECT 
    t.expected_table,
    CASE WHEN it.table_name IS NOT NULL THEN 'EXISTS' ELSE 'MISSING' END AS actual_status,
    COALESCE(it.table_schema, 'public') AS table_schema,
    t.relevant_migration
FROM (
    VALUES 
        ('users', 'V1__init_schema.sql'),
        ('shops', 'V1__init_schema.sql'),
        ('subscriptions', 'V1__init_schema.sql'),
        ('trial_eligibility_ledger', 'V1__init_schema.sql'),
        ('queue_sessions', 'V1__init_schema.sql'),
        ('queue_customers', 'V1__init_schema.sql'),
        ('application_settings', 'V1__init_schema.sql'),
        ('business_category_change_requests', 'V1__init_schema.sql'),
        ('business_category_change_audits', 'V1__init_schema.sql'),
        ('customer_push_subscriptions', 'V1__init_schema.sql'),
        ('businesses', 'V4__multi_business_multi_branch_and_user_seats.sql'),
        ('shop_memberships', 'V4__multi_business_multi_branch_and_user_seats.sql'),
        ('subscription_seats', 'V4__multi_business_multi_branch_and_user_seats.sql'),
        ('payment_records', 'V4__multi_business_multi_branch_and_user_seats.sql'),
        ('user_sessions', 'V4__multi_business_multi_branch_and_user_seats.sql'),
        ('audit_logs', 'V4__multi_business_multi_branch_and_user_seats.sql'),
        ('device_registrations', 'V5__device_bound_auth_and_bulk_branch_registration.sql'),
        ('device_replacement_requests', 'V5__device_bound_auth_and_bulk_branch_registration.sql'),
        ('device_auth_challenges', 'V6__device_auth_challenge_and_replacement_binding.sql'),
        ('idempotency_keys', 'V8__scalability_indexes_and_idempotency_keys.sql'),
        ('sse_tickets', 'V9__create_sse_tickets.sql'),
        ('_system_environment_canary', 'System / Provisioning Canary'),
        ('flyway_schema_history', 'Flyway Engine Metadata')
) AS t(expected_table, relevant_migration)
LEFT JOIN information_schema.tables it 
    ON it.table_name = t.expected_table 
   AND it.table_schema = 'public'
ORDER BY 
    CASE WHEN it.table_name IS NOT NULL THEN 0 ELSE 1 END,
    t.expected_table;

-- ------------------------------------------------------------------------------
-- QUERY 5: Live Record Counts for Core Application Tables (Rollback Verification)
-- Answers Phase 2: Did the failed transaction roll back or modify records?
-- ------------------------------------------------------------------------------
SELECT 
    'users' AS table_name, COUNT(*) AS row_count FROM users
UNION ALL SELECT 'businesses', COUNT(*) FROM businesses
UNION ALL SELECT 'shops', COUNT(*) FROM shops
UNION ALL SELECT 'shop_memberships', COUNT(*) FROM shop_memberships
UNION ALL SELECT 'subscriptions', COUNT(*) FROM subscriptions
UNION ALL SELECT 'trial_eligibility_ledger', COUNT(*) FROM trial_eligibility_ledger
UNION ALL SELECT 'queue_customers', COUNT(*) FROM queue_customers
UNION ALL SELECT 'queue_sessions', COUNT(*) FROM queue_sessions
UNION ALL SELECT 'audit_logs', COUNT(*) FROM audit_logs
UNION ALL SELECT 'payment_records', COUNT(*) FROM payment_records
UNION ALL SELECT 'user_sessions', COUNT(*) FROM user_sessions
UNION ALL SELECT 'customer_push_subscriptions', COUNT(*) FROM customer_push_subscriptions
UNION ALL SELECT 'application_settings', COUNT(*) FROM application_settings
UNION ALL SELECT 'flyway_schema_history', COUNT(*) FROM flyway_schema_history;

-- ------------------------------------------------------------------------------
-- QUERY 6: Live Foreign-Key Dependencies Across Schema 'public'
-- ------------------------------------------------------------------------------
SELECT
    tc.table_name AS child_table,
    kcu.column_name AS foreign_key_column,
    ccu.table_name AS parent_table,
    ccu.column_name AS parent_column,
    rc.delete_rule
FROM information_schema.table_constraints AS tc
JOIN information_schema.key_column_usage AS kcu
  ON tc.constraint_name = kcu.constraint_name
  AND tc.table_schema = kcu.table_schema
JOIN information_schema.constraint_column_usage AS ccu
  ON ccu.constraint_name = tc.constraint_name
  AND ccu.table_schema = tc.table_schema
JOIN information_schema.referential_constraints AS rc
  ON rc.constraint_name = tc.constraint_name
  AND rc.constraint_schema = tc.table_schema
WHERE tc.constraint_type = 'FOREIGN KEY'
  AND tc.table_schema = 'public'
ORDER BY tc.table_name, kcu.column_name;

-- ------------------------------------------------------------------------------
-- QUERY 7: Inspect Actual Columns on 'shops' and 'users' Tables
-- Answers: Whether V10 (public_merchant_code) and V11 (business_type) exist
-- ------------------------------------------------------------------------------
SELECT 
    table_name,
    column_name,
    data_type,
    is_nullable,
    column_default
FROM information_schema.columns
WHERE table_schema = 'public' 
  AND table_name IN ('shops', 'users')
ORDER BY table_name, ordinal_position;

