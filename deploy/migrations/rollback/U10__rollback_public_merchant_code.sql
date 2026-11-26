-- =========================================================================
-- QueueLess Database Migration: U10__rollback_public_merchant_code.sql
-- Purpose: Emergency rollback and archival procedure for public_merchant_code
-- CAUTION: NEVER drop public_merchant_code without archiving first!
--          Dropping this column permanently breaks all physical QR codes, table
--          tents, and distributed join links for merchants.
-- =========================================================================

-- =========================================================================
-- STEP 1: MANDATORY DATA PRESERVATION SNAPSHOT
-- Run this BEFORE any DDL rollback to ensure merchant codes can be restored.
-- =========================================================================
CREATE TABLE IF NOT EXISTS shops_public_merchant_code_archive (
    shop_id BIGINT PRIMARY KEY,
    public_merchant_code VARCHAR(32) NOT NULL,
    archived_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO shops_public_merchant_code_archive (shop_id, public_merchant_code)
SELECT id, public_merchant_code
FROM shops
WHERE public_merchant_code IS NOT NULL AND public_merchant_code <> ''
ON CONFLICT (shop_id) DO UPDATE
SET public_merchant_code = EXCLUDED.public_merchant_code,
    archived_at = CURRENT_TIMESTAMP;

-- =========================================================================
-- STEP 2: APPLICATION-ONLY ROLLBACK (PREFERRED & RECOMMENDED)
-- Roll back the application container to the previous release. Leave the
-- database column and unique index intact so physical QR codes never break.
-- =========================================================================

-- =========================================================================
-- STEP 3: DDL ROLLBACK (ONLY IF COLUMN REMOVAL IS STRICTLY MANDATED)
-- UNCOMMENT ONLY UPON EXPLICIT ARCHITECTURAL APPROVAL:
--
-- DROP INDEX IF EXISTS idx_shops_public_merchant_code;
-- ALTER TABLE shops DROP COLUMN IF EXISTS public_merchant_code;
-- =========================================================================

-- =========================================================================
-- STEP 4: EMERGENCY RESTORATION FROM ARCHIVE
-- If column was dropped and re-added, restore original codes from snapshot:
--
-- UPDATE shops s
-- SET public_merchant_code = a.public_merchant_code
-- FROM shops_public_merchant_code_archive a
-- WHERE s.id = a.shop_id;
-- =========================================================================
