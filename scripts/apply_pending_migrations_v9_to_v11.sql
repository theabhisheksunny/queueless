-- ==============================================================================
-- QueueLess — Pending Release Migrations V9, V10, V11 (Manual Supabase Apply)
-- Target Database: Supabase PostgreSQL 16 (Project: tlgibnjprelzpxfxycab)
-- Objective: Ensure schema is fully upgraded to V11 prior to clean launch reset
-- Execution: Run in Supabase SQL Editor if V9, V10, V11 are not in flyway_schema_history
-- Invariant: 100% Idempotent (safe to run multiple times, uses IF NOT EXISTS)
-- ==============================================================================

BEGIN;

-- ------------------------------------------------------------------------------
-- 1. Migration V9: Create Distributed Single-Use SSE Ticket Store
-- ------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sse_tickets (
    ticket VARCHAR(64) PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    shop_id BIGINT NOT NULL REFERENCES shops(id) ON DELETE CASCADE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_sse_tickets_expires_at ON sse_tickets(expires_at);
CREATE INDEX IF NOT EXISTS idx_sse_tickets_shop_user ON sse_tickets(shop_id, user_id);

-- ------------------------------------------------------------------------------
-- 2. Migration V10: Add Opaque Public Merchant Code to Shops (Expand Stage)
-- ------------------------------------------------------------------------------
CREATE SCHEMA IF NOT EXISTS extensions;
CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA extensions;

ALTER TABLE shops ADD COLUMN IF NOT EXISTS public_merchant_code VARCHAR(32);

-- Backfill any existing shops with CSPRNG codes if present
DO $$
DECLARE
    r RECORD;
    new_code VARCHAR(32);
    chars CONSTANT TEXT := '0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ';
    rnd_bytes BYTEA;
    byte_val INT;
    collision_count INT;
    retries INT;
    i INT;
BEGIN
    PERFORM set_config('search_path', 'public, extensions', true);

    FOR r IN SELECT id FROM shops WHERE public_merchant_code IS NULL OR public_merchant_code = '' LOOP
        retries := 0;
        LOOP
            new_code := 'pm_';
            WHILE length(new_code) < 19 LOOP
                rnd_bytes := gen_random_bytes(32);
                FOR i IN 0..31 LOOP
                    byte_val := get_byte(rnd_bytes, i);
                    IF byte_val < 248 AND length(new_code) < 19 THEN
                        new_code := new_code || substr(chars, (byte_val % 62) + 1, 1);
                    END IF;
                END LOOP;
            END LOOP;

            SELECT COUNT(*) INTO collision_count FROM shops WHERE public_merchant_code = new_code;
            EXIT WHEN collision_count = 0;

            retries := retries + 1;
            IF retries > 100 THEN
                RAISE EXCEPTION 'Excessive uniqueness collisions encountered during public merchant code backfill';
            END IF;
        END LOOP;

        UPDATE shops SET public_merchant_code = new_code WHERE id = r.id;
    END LOOP;
END $$;

-- Unique index to prevent duplicate merchant codes
CREATE UNIQUE INDEX IF NOT EXISTS idx_shops_public_merchant_code ON shops(public_merchant_code);

-- ------------------------------------------------------------------------------
-- 3. Migration V11: Add Business Type to Shops
-- ------------------------------------------------------------------------------
ALTER TABLE shops ADD COLUMN IF NOT EXISTS business_type VARCHAR(100);

-- ------------------------------------------------------------------------------
-- 4. Authoritative Flyway Schema History Alignment
-- Inserts migration records only if they have not already been recorded by Flyway
-- ------------------------------------------------------------------------------
DO $$
DECLARE
    v_max_rank INT;
BEGIN
    SELECT COALESCE(MAX(installed_rank), 0) INTO v_max_rank FROM flyway_schema_history;

    -- Record V9 if not present
    IF NOT EXISTS (SELECT 1 FROM flyway_schema_history WHERE version = '9') THEN
        v_max_rank := v_max_rank + 1;
        INSERT INTO flyway_schema_history (
            installed_rank, version, description, type, script,
            checksum, installed_by, installed_on, execution_time, success
        ) VALUES (
            v_max_rank, '9', 'create sse tickets', 'SQL', 'V9__create_sse_tickets.sql',
            -784819874, CURRENT_USER, CURRENT_TIMESTAMP, 50, TRUE
        );
        RAISE NOTICE 'Recorded Flyway migration V9 in flyway_schema_history.';
    ELSE
        RAISE NOTICE 'Flyway migration V9 is already recorded.';
    END IF;

    -- Record V10 if not present
    IF NOT EXISTS (SELECT 1 FROM flyway_schema_history WHERE version = '10') THEN
        v_max_rank := v_max_rank + 1;
        INSERT INTO flyway_schema_history (
            installed_rank, version, description, type, script,
            checksum, installed_by, installed_on, execution_time, success
        ) VALUES (
            v_max_rank, '10', 'add public merchant code to shops', 'JDBC', 'db.migration.V10__add_public_merchant_code_to_shops',
            NULL, CURRENT_USER, CURRENT_TIMESTAMP, 100, TRUE
        );
        RAISE NOTICE 'Recorded Flyway migration V10 in flyway_schema_history.';
    ELSE
        RAISE NOTICE 'Flyway migration V10 is already recorded.';
    END IF;

    -- Record V11 if not present
    IF NOT EXISTS (SELECT 1 FROM flyway_schema_history WHERE version = '11') THEN
        v_max_rank := v_max_rank + 1;
        INSERT INTO flyway_schema_history (
            installed_rank, version, description, type, script,
            checksum, installed_by, installed_on, execution_time, success
        ) VALUES (
            v_max_rank, '11', 'add business type to shops', 'SQL', 'V11__add_business_type_to_shops.sql',
            578557561, CURRENT_USER, CURRENT_TIMESTAMP, 50, TRUE
        );
        RAISE NOTICE 'Recorded Flyway migration V11 in flyway_schema_history.';
    ELSE
        RAISE NOTICE 'Flyway migration V11 is already recorded.';
    END IF;
END $$;

COMMIT;
