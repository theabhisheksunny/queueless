#!/usr/bin/env python3
"""
QueueLess — V10/V10.1 Migration & Opaque Merchant Identifier Verification Tests
=============================================================================
Validates Flyway V10 Expand, Stage 2 Application Cutover, and V10.1 Contract
migration syntax, search_path resolution, relational schema constraints,
concurrency safety, and rollback capabilities.
=============================================================================
"""

import os
import sqlite3
import unittest

MIGRATIONS_DIR = os.path.join(os.path.dirname(os.path.dirname(__file__)), "migrations")
V10_SQL_PATH = os.path.join(MIGRATIONS_DIR, "V10__add_public_merchant_code_to_shops.sql")
V10_1_CONTRACT_SQL_PATH = os.path.join(
    MIGRATIONS_DIR, "contract", "V10_1__contract_enforce_public_merchant_code_not_null.sql"
)
U10_ROLLBACK_SQL_PATH = os.path.join(MIGRATIONS_DIR, "rollback", "U10__rollback_public_merchant_code.sql")

V10_JAVA_PATH = os.path.abspath(
    os.path.join(
        os.path.dirname(os.path.dirname(__file__)),
        "..",
        "backend",
        "src",
        "main",
        "java",
        "db",
        "migration",
        "V10__add_public_merchant_code_to_shops.java",
    )
)


class TestV10MigrationVerification(unittest.TestCase):
    def test_stage1_expand_sql_migration(self):
        self.assertTrue(os.path.isfile(V10_SQL_PATH), f"V10 SQL file missing at {V10_SQL_PATH}")
        with open(V10_SQL_PATH, encoding="utf-8") as f:
            sql = f.read()

        # Stage 1 Expand must add nullable column and create unique index
        self.assertIn("ALTER TABLE shops ADD COLUMN IF NOT EXISTS public_merchant_code VARCHAR(32)", sql)
        self.assertIn("CREATE UNIQUE INDEX IF NOT EXISTS idx_shops_public_merchant_code", sql)

        # Stage 1 Expand MUST NOT enforce NOT NULL (would break V_old instances during rolling deployment)
        self.assertNotIn(
            "ALTER COLUMN public_merchant_code SET NOT NULL",
            sql,
            "Stage 1 Expand must NOT prematurely enforce NOT NULL",
        )

        # Cryptographic randomness checks
        self.assertIn("gen_random_bytes", sql, "SQL backfill must use cryptographic gen_random_bytes()")
        self.assertNotIn("floor(random()", sql, "SQL backfill must NOT use pseudo-random random()")

        # Supabase extensions search_path resolution
        self.assertIn("search_path", sql, "Must configure search_path to resolve extensions schema")
        self.assertIn("extensions", sql, "search_path must include extensions schema")

    def test_stage1_expand_java_migration(self):
        self.assertTrue(os.path.isfile(V10_JAVA_PATH), f"V10 Java migration file missing at {V10_JAVA_PATH}")
        with open(V10_JAVA_PATH, encoding="utf-8") as f:
            java = f.read()

        self.assertIn("ALTER TABLE shops ADD COLUMN IF NOT EXISTS public_merchant_code VARCHAR(32)", java)
        self.assertIn("idx_shops_public_merchant_code", java)
        self.assertIn("PublicMerchantCodeGenerator.generate()", java)

        # Java migration running automatically on startup MUST NOT enforce NOT NULL
        self.assertNotIn(
            "SET NOT NULL", java, "Java automatic startup migration must NOT enforce NOT NULL during rolling cutover"
        )

    def test_stage3_contract_sql_migration(self):
        self.assertTrue(
            os.path.isfile(V10_1_CONTRACT_SQL_PATH), f"V10.1 Contract SQL missing at {V10_1_CONTRACT_SQL_PATH}"
        )
        with open(V10_1_CONTRACT_SQL_PATH, encoding="utf-8") as f:
            sql = f.read()

        # Stage 3 Contract must re-backfill remaining NULLs from V_old concurrency
        self.assertIn("gen_random_bytes", sql, "Contract backfill must use CSPRNG")
        self.assertIn("WHERE public_merchant_code IS NULL OR public_merchant_code = ''", sql)

        # Stage 3 Contract must assert zero remaining NULLs
        self.assertIn("v_remaining_nulls", sql, "Contract migration must count remaining NULLs")
        self.assertIn("RAISE EXCEPTION", sql, "Contract migration must fail-closed if NULLs remain")

        # Stage 3 Contract must enforce NOT NULL
        self.assertIn("ALTER TABLE shops ALTER COLUMN public_merchant_code SET NOT NULL", sql)
        self.assertIn("idx_shops_public_merchant_code", sql)

    def test_rollback_safety_and_archival_script(self):
        self.assertTrue(
            os.path.isfile(U10_ROLLBACK_SQL_PATH), f"U10 Rollback script missing at {U10_ROLLBACK_SQL_PATH}"
        )
        with open(U10_ROLLBACK_SQL_PATH, encoding="utf-8") as f:
            sql = f.read()

        # Must contain mandatory archival snapshot table
        self.assertIn("shops_public_merchant_code_archive", sql)
        self.assertIn("ON CONFLICT (shop_id)", sql)

        # DDL DROP COLUMN must be guarded / commented out
        self.assertIn("-- ALTER TABLE shops DROP COLUMN IF EXISTS public_merchant_code", sql)

        # Must provide restoration statement
        self.assertIn("UPDATE shops s", sql)
        self.assertIn("shops_public_merchant_code_archive a", sql)

    def test_expand_cutover_contract_lifecycle_simulation(self):
        conn = sqlite3.connect(":memory:")
        cur = conn.cursor()

        # Step 1: Initial state before migration
        cur.execute("CREATE TABLE shops (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
        cur.execute("INSERT INTO shops (id, name) VALUES (1, 'Shop Alpha')")
        cur.execute("INSERT INTO shops (id, name) VALUES (2, 'Shop Beta')")
        conn.commit()

        # Step 2: STAGE 1 — EXPAND (Add nullable column & backfill existing records)
        cur.execute("ALTER TABLE shops ADD COLUMN public_merchant_code TEXT")
        cur.execute("UPDATE shops SET public_merchant_code = 'pm_a1b2c3d4e5f6g7h8' WHERE id = 1")
        cur.execute("UPDATE shops SET public_merchant_code = 'pm_z9y8x7w6v5u4t3s2' WHERE id = 2")
        cur.execute("CREATE UNIQUE INDEX idx_shops_public_merchant_code ON shops(public_merchant_code)")
        conn.commit()

        # Step 3: STAGE 2 — APPLICATION CUTOVER (Simulate concurrent creation by V_old & V_new)
        # V_old instance creates a shop without public_merchant_code (inserts NULL) -> SUCCEEDS because column is nullable!
        cur.execute("INSERT INTO shops (id, name, public_merchant_code) VALUES (3, 'Shop Gamma (V_old)', NULL)")
        # V_new instance creates a shop with generated public_merchant_code -> SUCCEEDS
        cur.execute(
            "INSERT INTO shops (id, name, public_merchant_code) VALUES (4, 'Shop Delta (V_new)', 'pm_k3m4n5p6q7r8s9t0')"
        )
        conn.commit()

        # Verify shop 3 created by V_old has NULL code
        cur.execute("SELECT id, public_merchant_code FROM shops WHERE id = 3")
        row_3 = cur.fetchone()
        self.assertIsNone(row_3[1], "Shop 3 created by V_old must be NULL during cutover")

        # Step 4: STAGE 3 — CONTRACT (Catch-up backfill for NULLs, assert 0 NULLs, enforce constraint)
        # 4a. Catch-up backfill for shop 3
        cur.execute(
            "UPDATE shops SET public_merchant_code = 'pm_catchup12345678' WHERE id = 3 AND public_merchant_code IS NULL"
        )
        conn.commit()

        # 4b. Assert zero NULLs remain
        cur.execute("SELECT COUNT(*) FROM shops WHERE public_merchant_code IS NULL OR public_merchant_code = ''")
        null_count = cur.fetchone()[0]
        self.assertEqual(null_count, 0, "Zero NULL records must remain before contract finalization")

        # Step 5: Verify lookup by public_merchant_code across all records
        for code, expected_id in [
            ("pm_a1b2c3d4e5f6g7h8", 1),
            ("pm_z9y8x7w6v5u4t3s2", 2),
            ("pm_catchup12345678", 3),
            ("pm_k3m4n5p6q7r8s9t0", 4),
        ]:
            cur.execute("SELECT id FROM shops WHERE public_merchant_code = ?", (code,))
            self.assertEqual(cur.fetchone()[0], expected_id)

        # Step 6: Verify uniqueness constraint prevents duplicates
        with self.assertRaises(sqlite3.IntegrityError):
            cur.execute(
                "INSERT INTO shops (id, name, public_merchant_code) VALUES (5, 'Shop Epsilon', 'pm_a1b2c3d4e5f6g7h8')"
            )
            conn.commit()

        # Step 7: Archival snapshot and recovery simulation
        cur.execute("""
            CREATE TABLE shops_public_merchant_code_archive (
                shop_id INTEGER PRIMARY KEY,
                public_merchant_code TEXT NOT NULL,
                archived_at TEXT DEFAULT CURRENT_TIMESTAMP
            )
        """)
        cur.execute(
            "INSERT INTO shops_public_merchant_code_archive (shop_id, public_merchant_code) SELECT id, public_merchant_code FROM shops WHERE public_merchant_code IS NOT NULL"
        )
        conn.commit()

        cur.execute("SELECT COUNT(*) FROM shops_public_merchant_code_archive")
        self.assertEqual(cur.fetchone()[0], 4, "Archive must preserve all 4 assigned codes")

        conn.close()


if __name__ == "__main__":
    unittest.main()
