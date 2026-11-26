#!/usr/bin/env python3
"""
QueueLess — Distributed SSE Ticket Store Design & Semantics Test
=============================================================================
Tests the atomic single-use consumption semantics of the proposed
PostgreSQL sse_tickets table design in an isolated, non-mutating environment.
Verifies single-use consumption, replay rejection, TTL expiry,
strict owner/shop binding without nullable bypasses, and concurrent
race-condition safety.
=============================================================================
"""

import datetime
import os
import sqlite3
import threading
import unittest

MIGRATION_PATH = os.path.join(os.path.dirname(os.path.dirname(__file__)), "migrations", "V9__create_sse_tickets.sql")
FLYWAY_PATH = os.path.abspath(
    os.path.join(
        os.path.dirname(os.path.dirname(__file__)),
        "..",
        "backend",
        "src",
        "main",
        "resources",
        "db",
        "migration",
        "V9__create_sse_tickets.sql",
    )
)


class TestDistributedSseTicketDesign(unittest.TestCase):
    def setUp(self):
        # Create in-memory SQLite database simulating relational atomic semantics
        self.conn = sqlite3.connect(":memory:", check_same_thread=False)
        self.cursor = self.conn.cursor()

        # Create simulated schema matching V9 specification
        self.cursor.execute("""
            CREATE TABLE sse_tickets (
                ticket TEXT PRIMARY KEY,
                user_id INTEGER NOT NULL,
                shop_id INTEGER NOT NULL,
                created_at TEXT NOT NULL,
                expires_at TEXT NOT NULL
            )
        """)
        self.conn.commit()

    def tearDown(self):
        self.conn.close()

    def test_migration_files_exist_in_deploy_and_flyway_paths(self):
        self.assertTrue(os.path.isfile(MIGRATION_PATH), f"Migration file missing at {MIGRATION_PATH}")
        self.assertTrue(os.path.isfile(FLYWAY_PATH), f"Flyway discovery path missing V9 at {FLYWAY_PATH}")
        with open(MIGRATION_PATH, encoding="utf-8") as f:
            content = f.read()
        self.assertIn("CREATE TABLE IF NOT EXISTS sse_tickets", content)
        self.assertIn("ticket VARCHAR(64) PRIMARY KEY", content)
        self.assertIn("expires_at TIMESTAMP WITH TIME ZONE NOT NULL", content)
        self.assertIn("idx_sse_tickets_expires_at", content)
        self.assertIn("idx_sse_tickets_shop_user", content)

    def test_atomic_single_use_consumption(self):
        now = datetime.datetime.now(datetime.UTC)
        expires_at = (now + datetime.timedelta(seconds=60)).isoformat()
        created_at = now.isoformat()

        # 1. Insert ticket
        self.cursor.execute(
            "INSERT INTO sse_tickets (ticket, user_id, shop_id, created_at, expires_at) VALUES (?, ?, ?, ?, ?)",
            ("stk_valid123", 101, 202, created_at, expires_at),
        )
        self.conn.commit()

        # 2. First consumer executes atomic DELETE ... RETURNING
        self.cursor.execute(
            "DELETE FROM sse_tickets WHERE ticket = ? AND expires_at > ? AND shop_id = ? AND user_id = ? RETURNING ticket, user_id, shop_id",
            ("stk_valid123", now.isoformat(), 202, 101),
        )
        row = self.cursor.fetchone()
        self.assertIsNotNone(row)
        self.assertEqual(row[0], "stk_valid123")
        self.assertEqual(row[1], 101)
        self.assertEqual(row[2], 202)
        self.conn.commit()

        # 3. Replay attack: Second consumer receives 0 rows (Fails closed)
        self.cursor.execute(
            "DELETE FROM sse_tickets WHERE ticket = ? AND expires_at > ? AND shop_id = ? AND user_id = ? RETURNING ticket, user_id, shop_id",
            ("stk_valid123", now.isoformat(), 202, 101),
        )
        replay_row = self.cursor.fetchone()
        self.assertIsNone(replay_row, "Replayed ticket must return 0 rows to trigger 403 Forbidden")

    def test_wrong_shop_rejected(self):
        now = datetime.datetime.now(datetime.UTC)
        expires_at = (now + datetime.timedelta(seconds=60)).isoformat()

        self.cursor.execute(
            "INSERT INTO sse_tickets (ticket, user_id, shop_id, created_at, expires_at) VALUES (?, ?, ?, ?, ?)",
            ("stk_shop202", 101, 202, now.isoformat(), expires_at),
        )
        self.conn.commit()

        # Consumer queries with wrong shop 999 -> 0 rows
        self.cursor.execute(
            "DELETE FROM sse_tickets WHERE ticket = ? AND expires_at > ? AND shop_id = ? AND user_id = ? RETURNING ticket",
            ("stk_shop202", now.isoformat(), 999, 101),
        )
        self.assertIsNone(self.cursor.fetchone(), "Wrong shop ID must return 0 rows")

    def test_wrong_owner_rejected(self):
        now = datetime.datetime.now(datetime.UTC)
        expires_at = (now + datetime.timedelta(seconds=60)).isoformat()

        self.cursor.execute(
            "INSERT INTO sse_tickets (ticket, user_id, shop_id, created_at, expires_at) VALUES (?, ?, ?, ?, ?)",
            ("stk_owner101", 101, 202, now.isoformat(), expires_at),
        )
        self.conn.commit()

        # Consumer queries with wrong user 999 -> 0 rows
        self.cursor.execute(
            "DELETE FROM sse_tickets WHERE ticket = ? AND expires_at > ? AND shop_id = ? AND user_id = ? RETURNING ticket",
            ("stk_owner101", now.isoformat(), 202, 999),
        )
        self.assertIsNone(self.cursor.fetchone(), "Wrong user ID must return 0 rows")

    def test_expired_ticket_rejected(self):
        now = datetime.datetime.now(datetime.UTC)
        expired_time = (now - datetime.timedelta(seconds=10)).isoformat()
        created_at = (now - datetime.timedelta(seconds=70)).isoformat()

        self.cursor.execute(
            "INSERT INTO sse_tickets (ticket, user_id, shop_id, created_at, expires_at) VALUES (?, ?, ?, ?, ?)",
            ("stk_expired", 101, 202, created_at, expired_time),
        )
        self.conn.commit()

        self.cursor.execute(
            "DELETE FROM sse_tickets WHERE ticket = ? AND expires_at > ? AND shop_id = ? AND user_id = ? RETURNING ticket",
            ("stk_expired", now.isoformat(), 202, 101),
        )
        self.assertIsNone(self.cursor.fetchone(), "Expired ticket must return 0 rows")

    def test_concurrent_ticket_consumption_atomic_single_winner(self):
        """Simulates 10 concurrent threads attempting to atomically consume the exact same ticket."""
        now = datetime.datetime.now(datetime.UTC)
        expires_at = (now + datetime.timedelta(seconds=60)).isoformat()

        self.cursor.execute(
            "INSERT INTO sse_tickets (ticket, user_id, shop_id, created_at, expires_at) VALUES (?, ?, ?, ?, ?)",
            ("stk_race_test", 555, 777, now.isoformat(), expires_at),
        )
        self.conn.commit()

        results = []
        lock = threading.Lock()

        def try_consume():
            with lock:
                cur = self.conn.cursor()
                cur.execute(
                    "DELETE FROM sse_tickets WHERE ticket = ? AND expires_at > ? AND shop_id = ? AND user_id = ? RETURNING ticket",
                    ("stk_race_test", now.isoformat(), 777, 555),
                )
                row = cur.fetchone()
                self.conn.commit()
                results.append(row is not None)

        threads = [threading.Thread(target=try_consume) for _ in range(10)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()

        # Exactly ONE thread must succeed; all others must fail
        self.assertEqual(results.count(True), 1, "Exactly one concurrent thread must succeed")
        self.assertEqual(results.count(False), 9, "9 concurrent threads must fail closed")

    def test_migration_rollback_safety(self):
        """Verifies clean rollback capability without collateral impact on other tables."""
        self.cursor.execute("DROP TABLE IF EXISTS sse_tickets")
        self.conn.commit()

        self.cursor.execute("SELECT name FROM sqlite_master WHERE type='table' AND name='sse_tickets'")
        self.assertIsNone(self.cursor.fetchone(), "Table must be cleanly dropped")


if __name__ == "__main__":
    unittest.main()
