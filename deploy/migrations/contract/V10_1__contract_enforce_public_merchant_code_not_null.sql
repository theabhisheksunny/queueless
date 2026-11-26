-- =========================================================================
-- QueueLess Database Migration: V10_1__contract_enforce_public_merchant_code_not_null.sql
-- Stage: STAGE 3 — CONTRACT (Post-Cutover)
-- Objective: Verify all shops have valid codes and enforce NOT NULL constraint
-- Execution: Run ONLY after Stage 2 application cutover is 100% complete and
--            all V_old backend instances have been terminated.
-- =========================================================================

-- 1. Ensure search_path resolves extensions (where Supabase provisions pgcrypto)
SET LOCAL search_path = public, extensions;

-- 2. Catch-up Backfill: Repeat CSPRNG backfill for any records created by V_old instances
-- during the rolling cutover window before those instances were retired.
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
    v_remaining_nulls INT;
BEGIN
    PERFORM set_config('search_path', 'public, extensions', true);

    -- Catch-up backfill loop
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
                RAISE EXCEPTION 'Excessive uniqueness collisions during Stage 3 contract backfill for shop id %', r.id;
            END IF;
        END LOOP;

        UPDATE shops SET public_merchant_code = new_code WHERE id = r.id;
    END LOOP;

    -- 3. Assert zero NULL or empty values remain
    SELECT COUNT(*) INTO v_remaining_nulls FROM shops WHERE public_merchant_code IS NULL OR public_merchant_code = '';
    IF v_remaining_nulls > 0 THEN
        RAISE EXCEPTION 'Stage 3 Contract Assertion Failed: % shops still have NULL or empty public_merchant_code. Aborting NOT NULL enforcement.', v_remaining_nulls;
    END IF;
END $$;

-- 4. Verify unique index exists before setting NOT NULL
CREATE UNIQUE INDEX IF NOT EXISTS idx_shops_public_merchant_code ON shops(public_merchant_code);

-- 5. Enforce NOT NULL constraint on public_merchant_code
ALTER TABLE shops ALTER COLUMN public_merchant_code SET NOT NULL;

-- STAGE 3 CONTRACT COMPLETE:
-- Column public_merchant_code is guaranteed populated and enforced NOT NULL.
