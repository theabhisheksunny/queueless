#!/usr/bin/env python3
"""
Unit tests for scripts/promote_image.py
Validates collision handling, idempotent retry, digest mismatch, exponential backoff, and crane copy flow.
"""

import unittest
from unittest.mock import patch, MagicMock, call
import sys
from pathlib import Path

# Add scripts directory to path
SCRIPTS_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPTS_DIR))

import promote_image

class TestPromoteImage(unittest.TestCase):

    def setUp(self):
        self.source_image = "asia-south1-docker.pkg.dev/proj/queueless-staging/backend@sha256:1111222233334444555566667777888899990000aaaabbbbccccddddeeeeffff"
        self.expected_digest = "sha256:1111222233334444555566667777888899990000aaaabbbbccccddddeeeeffff"
        self.target_repo = "asia-south1-docker.pkg.dev/proj/queueless-prod/backend"
        self.target_tag = "sha-abcdef1"

    @patch("promote_image.run_cmd")
    def test_normal_promotion_success(self, mock_run_cmd):
        # 1. query_remote_tag_digest for existing tag returns empty (tag does not exist)
        # 2. crane copy succeeds (code 0)
        # 3. query_remote_tag_digest for new tag returns expected_digest
        mock_run_cmd.side_effect = [
            (0, "", ""),                           # existing check: not found
            (0, "crane copy successful", ""),       # crane copy
            (0, self.expected_digest, "")           # post-copy check
        ]

        result_uri = promote_image.promote_image(
            source_image=self.source_image,
            target_repo=self.target_repo,
            target_tag=self.target_tag,
            expected_digest=self.expected_digest
        )

        expected_uri = f"{self.target_repo}@{self.expected_digest}"
        self.assertEqual(result_uri, expected_uri)
        self.assertEqual(mock_run_cmd.call_count, 3)

    @patch("promote_image.run_cmd")
    def test_idempotent_retry_existing_tag_matching_digest(self, mock_run_cmd):
        # Existing tag check returns expected_digest
        # Should skip crane copy entirely
        mock_run_cmd.side_effect = [
            (0, self.expected_digest, "")
        ]

        result_uri = promote_image.promote_image(
            source_image=self.source_image,
            target_repo=self.target_repo,
            target_tag=self.target_tag,
            expected_digest=self.expected_digest
        )

        expected_uri = f"{self.target_repo}@{self.expected_digest}"
        self.assertEqual(result_uri, expected_uri)
        # Only 1 call to check existing tag; crane copy never invoked
        self.assertEqual(mock_run_cmd.call_count, 1)

    @patch("promote_image.run_cmd")
    def test_tag_collision_mismatched_digest_fails_closed(self, mock_run_cmd):
        # Existing tag check returns DIFFERENT digest
        # Must fail closed immediately
        different_digest = "sha256:9999888877776666555544443333222211110000aaaabbbbccccddddeeeeffff"
        mock_run_cmd.side_effect = [
            (0, different_digest, "")
        ]

        with self.assertRaises(SystemExit) as cm:
            promote_image.promote_image(
                source_image=self.source_image,
                target_repo=self.target_repo,
                target_tag=self.target_tag,
                expected_digest=self.expected_digest
            )

        self.assertEqual(cm.exception.code, 1)
        self.assertEqual(mock_run_cmd.call_count, 1)

    @patch("time.sleep")
    @patch("promote_image.run_cmd")
    def test_crane_copy_transient_failure_then_success(self, mock_run_cmd, mock_sleep):
        # Attempt 1 fails, attempt 2 succeeds
        mock_run_cmd.side_effect = [
            (0, "", ""),                           # check existing: none
            (1, "", "crane: network glitch"),       # copy attempt 1: fail
            (0, "crane: copy ok", ""),             # copy attempt 2: success
            (0, self.expected_digest, "")           # post-copy verify
        ]

        result_uri = promote_image.promote_image(
            source_image=self.source_image,
            target_repo=self.target_repo,
            target_tag=self.target_tag,
            expected_digest=self.expected_digest
        )

        self.assertEqual(result_uri, f"{self.target_repo}@{self.expected_digest}")
        self.assertEqual(mock_run_cmd.call_count, 4)
        mock_sleep.assert_called_once_with(2) # 2**1 = 2s backoff on attempt 1

    @patch("time.sleep")
    @patch("promote_image.run_cmd")
    def test_crane_copy_all_retries_fail_closed(self, mock_run_cmd, mock_sleep):
        # All 3 attempts fail
        mock_run_cmd.side_effect = [
            (0, "", ""),                     # check existing: none
            (1, "", "transient error 1"),     # attempt 1
            (1, "", "transient error 2"),     # attempt 2
            (1, "", "transient error 3")      # attempt 3
        ]

        with self.assertRaises(SystemExit) as cm:
            promote_image.promote_image(
                source_image=self.source_image,
                target_repo=self.target_repo,
                target_tag=self.target_tag,
                expected_digest=self.expected_digest,
                max_retries=3
            )

        self.assertEqual(cm.exception.code, 1)
        self.assertEqual(mock_run_cmd.call_count, 4)

    @patch("promote_image.run_cmd")
    def test_post_copy_digest_mismatch_fails_closed(self, mock_run_cmd):
        # 1. tag does not exist
        # 2. crane copy succeeds
        # 3. post-copy check returns mismatched digest
        corrupted_digest = "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
        mock_run_cmd.side_effect = [
            (0, "", ""),
            (0, "crane copy ok", ""),
            (0, corrupted_digest, "")
        ]

        with self.assertRaises(SystemExit) as cm:
            promote_image.promote_image(
                source_image=self.source_image,
                target_repo=self.target_repo,
                target_tag=self.target_tag,
                expected_digest=self.expected_digest
            )

        self.assertEqual(cm.exception.code, 1)

    def test_invalid_expected_digest_format(self):
        with self.assertRaises(ValueError):
            promote_image.promote_image(
                source_image=self.source_image,
                target_repo=self.target_repo,
                target_tag=self.target_tag,
                expected_digest="invalid-digest-format"
            )

    def test_source_image_digest_mismatch(self):
        with self.assertRaises(ValueError):
            promote_image.promote_image(
                source_image="asia-south1-docker.pkg.dev/proj/queueless-staging/backend@sha256:different",
                target_repo=self.target_repo,
                target_tag=self.target_tag,
                expected_digest=self.expected_digest
            )

if __name__ == "__main__":
    unittest.main()
