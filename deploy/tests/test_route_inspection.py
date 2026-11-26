#!/usr/bin/env python3
"""
QueueLess — Unit & Integration Tests for Route Inspection & State Capture
=============================================================================
Tests read-only Cloudflare route inspection, provenance enforcement,
manifest generation, error handling, and zero-secrets security invariant.
=============================================================================
"""

import json
import os
import sys
import unittest
import urllib.error
from unittest.mock import MagicMock, patch

# Ensure scripts directory is in python path
SCRIPTS_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "scripts")
if SCRIPTS_DIR not in sys.path:
    sys.path.insert(0, SCRIPTS_DIR)

from capture_deployment_state import capture_staging_baseline, probe_json_endpoint
from inspect_edge_route import (
    discover_cloudflare_credentials,
    inspect_live_worker_binding,
)


class TestRouteInspectionAndStateCapture(unittest.TestCase):
    def test_cloudflare_api_success_provenance_live(self):
        """Test successful Cloudflare API binding retrieval with LIVE_CLOUDFLARE_API provenance."""
        mock_response = MagicMock()
        mock_response.getcode.return_value = 200
        mock_response.read.return_value = json.dumps(
            {
                "result": [
                    {"name": "ASSETS", "type": "assets"},
                    {
                        "name": "BACKEND_API_URL",
                        "text": "https://queueless-backend-staging.onrender.com",
                        "type": "plain_text",
                    },
                ],
                "success": True,
            }
        ).encode("utf-8")
        mock_response.__enter__.return_value = mock_response

        with patch("urllib.request.urlopen", return_value=mock_response):
            result = inspect_live_worker_binding("queueless-staging", "staging", "test_acc", "test_token")
            self.assertEqual(result["provenance"], "LIVE_CLOUDFLARE_API")
            self.assertEqual(result["status"], "FOUND")
            self.assertTrue(result["worker_exists"])
            self.assertEqual(result["backend_api_url"], "https://queueless-backend-staging.onrender.com")

    def test_cloudflare_api_404_worker_not_found(self):
        """Test that HTTP 404 from Cloudflare API reports NOT_FOUND and does NOT substitute a fallback."""
        http_err = urllib.error.HTTPError(
            url="https://api.cloudflare.com/client/v4/accounts/test_acc/workers/scripts/queueless-staging/bindings",
            code=404,
            msg="Not Found",
            hdrs={},
            fp=MagicMock(
                read=lambda: (
                    b'{"success":false,"errors":[{"code":10007,"message":"This Worker does not exist on your account."}]}'
                )
            ),
        )

        with patch("urllib.request.urlopen", side_effect=http_err):
            result = inspect_live_worker_binding("queueless-staging", "staging", "test_acc", "test_token")
            self.assertEqual(result["status"], "NOT_FOUND")
            self.assertFalse(result["worker_exists"])
            self.assertIsNone(result["backend_api_url"])
            self.assertIn("does not exist", result["error_details"])

    def test_cloudflare_api_missing_backend_binding(self):
        """Test that worker with missing BACKEND_API_URL returns NO_BACKEND_BINDING."""
        mock_response = MagicMock()
        mock_response.getcode.return_value = 200
        mock_response.read.return_value = json.dumps(
            {"result": [{"name": "ASSETS", "type": "assets"}], "success": True}
        ).encode("utf-8")
        mock_response.__enter__.return_value = mock_response

        with patch("urllib.request.urlopen", return_value=mock_response):
            result = inspect_live_worker_binding("queueless-staging", "staging", "test_acc", "test_token")
            self.assertEqual(result["status"], "NO_BACKEND_BINDING")
            self.assertTrue(result["worker_exists"])
            self.assertIsNone(result["backend_api_url"])

    def test_missing_credentials_handling(self):
        """Test discovery behavior when no credentials are provided."""
        with patch.dict(os.environ, {}, clear=True), patch("os.path.exists", return_value=False):
            acc, tok, source = discover_cloudflare_credentials(None, None)
            self.assertIsNone(acc)
            self.assertIsNone(tok)
            self.assertEqual(source, "UNRESOLVED")

    def test_probe_json_endpoint_success(self):
        """Test JSON endpoint probe on healthy endpoint."""
        mock_response = MagicMock()
        mock_response.getcode.return_value = 200
        mock_response.read.return_value = b'{"status": "UP"}'
        mock_response.__enter__.return_value = mock_response

        with patch("urllib.request.urlopen", return_value=mock_response):
            res = probe_json_endpoint("http://example.com/actuator/health")
            self.assertTrue(res["reachable"])
            self.assertEqual(res["http_code"], 200)
            self.assertEqual(res["data"], {"status": "UP"})

    def test_probe_json_endpoint_error(self):
        """Test JSON endpoint probe on connection error."""
        with patch("urllib.request.urlopen", side_effect=Exception("Connection refused")):
            res = probe_json_endpoint("http://example.com/actuator/health")
            self.assertFalse(res["reachable"])
            self.assertIn("Connection refused", res["error"])

    def test_capture_staging_baseline_manifest_security_and_structure(self):
        """Test manifest compilation, ensuring zero secrets and strict schema structure."""
        mock_health = {"reachable": True, "http_code": 200, "data": {"status": "UP"}, "latency_ms": 50, "error": None}
        mock_edge = {"reachable": False, "http_code": 404, "data": None, "latency_ms": 30, "error": "HTTP 404"}

        with (
            patch(
                "capture_deployment_state.probe_json_endpoint",
                side_effect=[
                    mock_health,
                    mock_health,
                    {"reachable": True, "http_code": 200, "data": {"publicKey": "BF6lc..."}, "latency_ms": 40},
                    mock_edge,
                ],
            ),
            patch(
                "capture_deployment_state.discover_cloudflare_credentials", return_value=("acc123", "tok456", "MOCK")
            ),
            patch(
                "capture_deployment_state.inspect_live_worker_binding",
                return_value={
                    "status": "NOT_FOUND",
                    "backend_api_url": None,
                    "worker_exists": False,
                    "error_details": "Mock 404",
                },
            ),
        ):
            tmp_path = os.path.join(SCRIPTS_DIR, "test_manifest.tmp.json")
            try:
                manifest = capture_staging_baseline(env="staging", output_path=tmp_path)
                self.assertEqual(manifest["metadata"]["environment"], "staging")
                self.assertEqual(manifest["metadata"]["active_provider_in_config"], "render")
                self.assertFalse(manifest["edge_worker_state"]["worker_exists_on_cloudflare"])
                self.assertIn("UP", manifest["active_backend_provider"]["database_readiness"])

                # Security invariant: zero secret leakage
                manifest_str = json.dumps(manifest)
                self.assertNotIn("tok456", manifest_str)
                self.assertNotIn("password", manifest_str.lower())
                self.assertNotIn("bearer", manifest_str.lower())
                self.assertTrue(manifest["safety_audit"]["secrets_redacted"])
                self.assertFalse(manifest["safety_audit"]["live_infrastructure_mutated"])
            finally:
                if os.path.exists(tmp_path):
                    os.remove(tmp_path)


if __name__ == "__main__":
    unittest.main()
