-- =========================================================================
-- QueueLess Database Migration: V10__add_public_merchant_code_to_shops.sql
-- Stage: STAGE 1 — EXPAND
-- Objective: Add nullable public merchant identifier column and unique index
-- Execution: Standalone SQL migration for Stage 1. Supabase ONLY upon approval.
-- Note: Column remains NULLABLE to ensure compatibility with V_old application
--       instances during rolling deployment. NOT NULL enforced in Stage 3.
-- =========================================================================

-- 1. Ensure search_path resolves extensions (where Supabase provisions pgcrypto)
SET LOCAL search_path = public, extensions;

-- 2. Ensure pgcrypto extension is active for cryptographic random byte generation
-- (Prerequisite: Supabase has pgcrypto in 'extensions' schema by default)
CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA extensions;

-- 3. Add column as NULLABLE (Expand phase requirement)
ALTER TABLE shops ADD COLUMN IF NOT EXISTS public_merchant_code VARCHAR(32);

-- 4. Backfill existing records using Cryptographically Secure Random Generator (CSPRNG)
-- Uses pgcrypto gen_random_bytes() with rejection sampling (at 248 = 62 * 4) to eliminate modulo bias.
-- Strictly avoids non-cryptographic pseudo-random random().
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
    -- Ensure extensions schema is in search_path inside execution block
    PERFORM set_config('search_path', 'public, extensions', true);

    FOR r IN SELECT id FROM shops WHERE public_merchant_code IS NULL OR public_merchant_code = '' LOOP
        retries := 0;
        LOOP
            new_code := 'pm_';
            WHILE length(new_code) < 19 LOOP
                -- Draw 32 cryptographic random bytes from OS entropy source
                rnd_bytes := gen_random_bytes(32);
                FOR i IN 0..31 LOOP
                    byte_val := get_byte(rnd_bytes, i);
                    -- Rejection sampling (256 - (256 % 62) = 248) eliminates modulo bias
                    IF byte_val < 248 AND length(new_code) < 19 THEN
                        new_code := new_code || substr(chars, (byte_val % 62) + 1, 1);
                    END IF;
                END LOOP;
            END LOOP;

            -- Check for uniqueness collision against database
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

-- 5. Create Unique Index safely
CREATE UNIQUE INDEX IF NOT EXISTS idx_shops_public_merchant_code ON shops(public_merchant_code);

-- STAGE 1 EXPAND COMPLETE:
-- Column public_merchant_code is present, indexed, and backfilled.
-- Column intentionally remains NULLABLE until Stage 3 Contract migration.
-- For rollback procedures, see: deploy/migrations/rollback/U10__rollback_public_merchant_code.sql
