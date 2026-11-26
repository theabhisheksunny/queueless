#!/usr/bin/env python3
"""
QueueLess — Automated Edge Routing, Rollback, and Health Verification Tests
Validates:
1. Environment and worker target boundary isolation (staging vs production).
2. Missing Cloudflare API credential handling (fail closed).
3. Missing previous route / Cloudflare API retrieval failure (fail closed).
4. Previous backend health verification (refusing switch if rollback target is unhealthy).
5. Direct backend health timeout and error handling.
6. Edge VAPID payload validation (distinguishing real credentials from 200 error payloads).
7. Automatic rollback execution upon post-switch edge failure.
8. Rollback verification failure handling (critical error reporting).
9. Dry-run safety guarantees.
"""

import http.server
import os
import socketserver
import sys
import threading
import unittest
from unittest.mock import patch

SCRIPTS_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "scripts"))
sys.path.insert(0, SCRIPTS_DIR)

from rollback_edge_traffic import execute_rollback, verify_edge_vapid
from switch_edge_traffic import switch_edge_traffic
from verify_backend_health import verify_direct_backend


class MockHealthServerHandler(http.server.BaseHTTPRequestHandler):
    backend_status = "UP"  # "UP", "DOWN", "503"
    edge_mode = "healthy"  # "healthy", "error_payload", "down"

    def do_GET(self):
        if "/actuator/health" in self.path:
            if self.backend_status == "UP":
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(b'{"status":"UP","components":{"db":{"status":"UP"},"dbReadiness":{"status":"UP"}}}')
            elif self.backend_status == "DOWN":
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(
                    b'{"status":"DOWN","components":{"db":{"status":"DOWN"},"dbReadiness":{"status":"DOWN"}}}'
                )
            elif self.backend_status == "DB_READINESS_DOWN":
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(b'{"status":"UP","components":{"db":{"status":"UP"},"dbReadiness":{"status":"DOWN"}}}')
            else:
                self.send_response(503)
                self.end_headers()
        elif self.path.endswith("/api/public/queue/vapid-public-key"):
            if self.edge_mode == "healthy":
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(
                    b'{"publicKey":"BMzMbq-oyR9oFQ0X5T3djMuolazZ8lEkoanDMF9ZQmBagIKTuqIwytB6i24f8BxbzWmXAbGtvpV4orGa8AsP-0E"}'
                )
            elif self.edge_mode == "error_payload":
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(b'{"error":"Database connection pool exhausted"}')
            else:
                self.send_response(502)
                self.end_headers()
        else:
            self.send_response(404)
            self.end_headers()

    def log_message(self, format, *args):
        pass  # Suppress logging in test output


class TestRoutingAndHealthVerification(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = socketserver.TCPServer(("127.0.0.1", 0), MockHealthServerHandler)
        cls.port = cls.server.server_address[1]
        cls.server_thread = threading.Thread(target=cls.server.serve_forever)
        cls.server_thread.daemon = True
        cls.server_thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def setUp(self):
        MockHealthServerHandler.backend_status = "UP"
        MockHealthServerHandler.edge_mode = "healthy"

    # =========================================================================
    # 1. ENVIRONMENT BOUNDARY ISOLATION
    # =========================================================================

    def test_wrong_worker_environment_boundary_staging(self):
        # Targeting staging environment but passing production worker name 'queueless'
        success = switch_edge_traffic(
            env="staging",
            worker_name="queueless",
            new_backend_url="https://target.run.app",
            backend_health_url="https://target.run.app/actuator/health",
            edge_public_url="https://queueless-staging.workers.dev",
            current_backend_url="https://prev.run.app",
            dry_run=True,
        )
        self.assertFalse(success, "Cross-environment mismatch (staging with prod worker) must be rejected")

    def test_wrong_worker_environment_boundary_production(self):
        # Targeting production environment but passing staging worker name 'queueless-staging'
        success = switch_edge_traffic(
            env="production",
            worker_name="queueless-staging",
            new_backend_url="https://target.run.app",
            backend_health_url="https://target.run.app/actuator/health",
            edge_public_url="https://queueless.workers.dev",
            current_backend_url="https://prev.run.app",
            dry_run=True,
        )
        self.assertFalse(success, "Cross-environment mismatch (production with staging worker) must be rejected")

    # =========================================================================
    # 2. MISSING PREVIOUS ROUTE & FAIL-CLOSED CHECKS
    # =========================================================================

    def test_missing_previous_route_fails_closed(self):
        # With no Cloudflare API credentials and no current_backend_url, must fail closed
        with patch.dict(os.environ, {}, clear=True):
            success = switch_edge_traffic(
                env="staging",
                worker_name="queueless-staging",
                new_backend_url=f"http://127.0.0.1:{self.port}",
                backend_health_url=f"http://127.0.0.1:{self.port}/actuator/health",
                edge_public_url=f"http://127.0.0.1:{self.port}",
                current_backend_url=None,
                dry_run=False,
            )
            self.assertFalse(success, "Missing previous route must fail closed before any traffic mutation")

    @patch("switch_edge_traffic.get_live_backend_url", return_value=None)
    def test_cloudflare_api_retrieval_failure_fails_closed(self, mock_get_live):
        # Cloudflare API query fails, no fallback provided
        success = switch_edge_traffic(
            env="staging",
            worker_name="queueless-staging",
            new_backend_url=f"http://127.0.0.1:{self.port}",
            backend_health_url=f"http://127.0.0.1:{self.port}/actuator/health",
            edge_public_url=f"http://127.0.0.1:{self.port}",
            current_backend_url=None,
            dry_run=False,
        )
        self.assertFalse(success, "Cloudflare API retrieval failure must fail closed")

    def test_previous_backend_unhealthy_fails_closed(self):
        # Previous backend URL is known, but its health check fails
        MockHealthServerHandler.backend_status = "DOWN"
        success = switch_edge_traffic(
            env="staging",
            worker_name="queueless-staging",
            new_backend_url="https://new-healthy-backend.onrender.com",
            backend_health_url="https://new-healthy-backend.onrender.com/actuator/health",
            edge_public_url=f"http://127.0.0.1:{self.port}",
            current_backend_url=f"http://127.0.0.1:{self.port}",
            dry_run=False,
        )
        self.assertFalse(success, "Unhealthy previous backend must abort switch to protect rollback capability")

    def test_missing_cloudflare_credentials_rejected(self):
        MockHealthServerHandler.backend_status = "UP"
        with patch.dict(os.environ, {}, clear=True):
            # No CLOUDFLARE_API_TOKEN in environment
            success = switch_edge_traffic(
                env="staging",
                worker_name="queueless-staging",
                new_backend_url=f"http://127.0.0.1:{self.port}",
                backend_health_url=f"http://127.0.0.1:{self.port}/actuator/health",
                edge_public_url=f"http://127.0.0.1:{self.port}",
                current_backend_url=f"http://127.0.0.1:{self.port}",
                dry_run=False,
            )
            self.assertFalse(success, "Missing CLOUDFLARE_API_TOKEN must fail closed before updating edge")

    # =========================================================================
    # 3. DIRECT BACKEND HEALTH VERIFICATION & TIMEOUTS
    # =========================================================================

    def test_direct_backend_health_success(self):
        MockHealthServerHandler.backend_status = "UP"
        ok = verify_direct_backend(
            f"http://127.0.0.1:{self.port}/actuator/health", timeout_seconds=5, interval_seconds=1
        )
        self.assertTrue(ok, "Healthy direct backend should pass verification")

    def test_direct_backend_health_db_down_rejected(self):
        MockHealthServerHandler.backend_status = "DOWN"
        ok = verify_direct_backend(
            f"http://127.0.0.1:{self.port}/actuator/health", timeout_seconds=2, interval_seconds=1
        )
        self.assertFalse(ok, "Backend reporting db DOWN must fail verification")

    def test_direct_backend_health_timeout(self):
        # Port 9999 is inactive, should trigger URLError and timeout
        ok = verify_direct_backend("http://127.0.0.1:9999/actuator/health", timeout_seconds=2, interval_seconds=1)
        self.assertFalse(ok, "Unreachable backend URL must time out and return False")

    # =========================================================================
    # 4. EDGE VAPID VERIFICATION & PAYLOAD INTEGRITY
    # =========================================================================

    def test_edge_vapid_healthy_payload(self):
        MockHealthServerHandler.edge_mode = "healthy"
        ok = verify_edge_vapid(f"http://127.0.0.1:{self.port}", timeout_seconds=3, interval=1)
        self.assertTrue(ok, "Valid VAPID public key payload must pass verification")

    def test_edge_vapid_error_payload_rejected(self):
        # Server returns HTTP 200 but body contains {"error": "..."}
        MockHealthServerHandler.edge_mode = "error_payload"
        ok = verify_edge_vapid(f"http://127.0.0.1:{self.port}", timeout_seconds=2, interval=1)
        self.assertFalse(ok, "HTTP 200 containing error message must NOT pass verification")

    # =========================================================================
    # 5. AUTOMATIC ROLLBACK UPON POST-SWITCH ROUTING FAILURE
    # =========================================================================

    @patch("switch_edge_traffic.execute_rollback")
    @patch("switch_edge_traffic.subprocess.run")
    def test_new_backend_healthy_but_edge_verification_fails_triggers_rollback(
        self, mock_subprocess, mock_execute_rollback
    ):
        mock_subprocess.return_value.stdout = "Updated"
        mock_subprocess.return_value.returncode = 0
        mock_execute_rollback.return_value = True

        # Backend is healthy, but edge proxy returns 502/down
        MockHealthServerHandler.backend_status = "UP"
        MockHealthServerHandler.edge_mode = "down"

        prev_healthy_url = f"http://127.0.0.1:{self.port}"

        with patch.dict(os.environ, {"CLOUDFLARE_API_TOKEN": "mock_token"}):
            success = switch_edge_traffic(
                env="staging",
                worker_name="queueless-staging",
                new_backend_url=f"http://127.0.0.1:{self.port}",
                backend_health_url=f"http://127.0.0.1:{self.port}/actuator/health",
                edge_public_url=f"http://127.0.0.1:{self.port}",
                current_backend_url=prev_healthy_url,
                health_timeout=2,
                edge_timeout=2,
                dry_run=False,
            )
            self.assertFalse(success, "Failed edge verification must return False")
            # Verify that execute_rollback was invoked with the exact previously healthy URL!
            mock_execute_rollback.assert_called_once_with(
                "staging",
                "queueless-staging",
                prev_healthy_url,
                edge_public_url=f"http://127.0.0.1:{self.port}",
                dry_run=False,
            )

    @patch("rollback_edge_traffic.verify_edge_vapid", return_value=False)
    @patch("rollback_edge_traffic.subprocess.run")
    def test_rollback_verification_failure_reports_critical_error(self, mock_subprocess, mock_verify_edge):
        mock_subprocess.return_value.stdout = "Rollback applied"
        mock_subprocess.return_value.returncode = 0

        with patch.dict(os.environ, {"CLOUDFLARE_API_TOKEN": "mock_token"}):
            # Rollback command runs, but edge route verification fails
            ok = execute_rollback(
                env="staging",
                worker_name="queueless-staging",
                restore_backend_url="https://previously-healthy-backend.onrender.com",
                edge_public_url="https://queueless-staging.workers.dev",
                dry_run=False,
            )
            self.assertFalse(ok, "Rollback verification failure must report failure and return False")

    # =========================================================================
    # 6. DRY-RUN SAFETY GUARANTEE
    # =========================================================================

    def test_dry_run_switch_succeeds_without_mutations(self):
        success = switch_edge_traffic(
            env="staging",
            worker_name="queueless-staging",
            new_backend_url="https://queueless-backend-staging.onrender.com",
            backend_health_url="https://queueless-backend-staging.onrender.com/actuator/health",
            edge_public_url="https://queueless-staging.theabhisheksunny.workers.dev",
            current_backend_url="https://queueless-backend-staging.onrender.com",
            dry_run=True,
        )
        self.assertTrue(success, "Dry-run should succeed and simulate all gates")

    def test_dry_run_rollback_succeeds_without_mutations(self):
        success = execute_rollback(
            env="staging",
            worker_name="queueless-staging",
            restore_backend_url="https://queueless-backend-staging.onrender.com",
            edge_public_url="https://queueless-staging.theabhisheksunny.workers.dev",
            dry_run=True,
        )
        self.assertTrue(success, "Dry-run rollback should succeed without network mutations")

    # =========================================================================
    # 7. INITIAL WORKER BOOTSTRAP SUPPORT
    # =========================================================================

    @patch("switch_edge_traffic.verify_edge_vapid", return_value=True)
    @patch("switch_edge_traffic.subprocess.run")
    def test_initial_bootstrap_succeeds_without_previous_route(self, mock_subprocess, mock_verify_edge):
        mock_subprocess.return_value.stdout = "Bootstrap applied"
        mock_subprocess.return_value.returncode = 0
        MockHealthServerHandler.backend_status = "UP"

        with patch.dict(os.environ, {"CLOUDFLARE_API_TOKEN": "mock_token"}):
            success = switch_edge_traffic(
                env="staging",
                worker_name="queueless-staging",
                new_backend_url=f"http://127.0.0.1:{self.port}",
                backend_health_url=f"http://127.0.0.1:{self.port}/actuator/health",
                edge_public_url=f"http://127.0.0.1:{self.port}",
                current_backend_url=None,
                dry_run=False,
                initial_bootstrap=True,
            )
            self.assertTrue(success, "Initial bootstrap must succeed without pre-existing rollback route")
            mock_subprocess.assert_called_once()
            cmd_args = mock_subprocess.call_args[0][0]
            self.assertIn("--keep-vars", cmd_args)
            self.assertNotIn("--no-build", cmd_args)

    def test_initial_bootstrap_dry_run_succeeds(self):
        success = switch_edge_traffic(
            env="staging",
            worker_name="queueless-staging",
            new_backend_url="https://queueless-backend-staging.onrender.com",
            backend_health_url="https://queueless-backend-staging.onrender.com/actuator/health",
            edge_public_url="https://queueless-staging.theabhisheksunny.workers.dev",
            current_backend_url=None,
            dry_run=True,
            initial_bootstrap=True,
        )
        self.assertTrue(success, "Initial bootstrap dry-run must succeed without previous route")

    def test_initial_bootstrap_rejected_when_target_unhealthy(self):
        MockHealthServerHandler.backend_status = "DOWN"
        success = switch_edge_traffic(
            env="staging",
            worker_name="queueless-staging",
            new_backend_url=f"http://127.0.0.1:{self.port}",
            backend_health_url=f"http://127.0.0.1:{self.port}/actuator/health",
            edge_public_url=f"http://127.0.0.1:{self.port}",
            current_backend_url=None,
            health_timeout=2,
            dry_run=False,
            initial_bootstrap=True,
        )
        self.assertFalse(success, "Initial bootstrap must fail if target backend is unhealthy")

    @patch("switch_edge_traffic.subprocess.run")
    def test_initial_bootstrap_failed_first_deployment_reports_fatal(self, mock_subprocess):
        import subprocess

        mock_subprocess.side_effect = subprocess.CalledProcessError(
            returncode=1, cmd=["npx", "wrangler", "deploy"], stderr="Authentication failed or Cloudflare API error"
        )
        MockHealthServerHandler.backend_status = "UP"

        with patch.dict(os.environ, {"CLOUDFLARE_API_TOKEN": "mock_token"}):
            success = switch_edge_traffic(
                env="staging",
                worker_name="queueless-staging",
                new_backend_url=f"http://127.0.0.1:{self.port}",
                backend_health_url=f"http://127.0.0.1:{self.port}/actuator/health",
                edge_public_url=f"http://127.0.0.1:{self.port}",
                current_backend_url=None,
                dry_run=False,
                initial_bootstrap=True,
            )
            self.assertFalse(success, "Failed first deployment must report fatal and return False")

    @patch("switch_edge_traffic.verify_edge_vapid", return_value=False)
    @patch("switch_edge_traffic.subprocess.run")
    def test_initial_bootstrap_edge_verification_failure_reports_fatal_without_rollback(
        self, mock_subprocess, mock_verify_edge
    ):
        mock_subprocess.return_value.stdout = "Bootstrap applied"
        mock_subprocess.return_value.returncode = 0
        MockHealthServerHandler.backend_status = "UP"

        with patch.dict(os.environ, {"CLOUDFLARE_API_TOKEN": "mock_token"}):
            success = switch_edge_traffic(
                env="staging",
                worker_name="queueless-staging",
                new_backend_url=f"http://127.0.0.1:{self.port}",
                backend_health_url=f"http://127.0.0.1:{self.port}/actuator/health",
                edge_public_url=f"http://127.0.0.1:{self.port}",
                current_backend_url=None,
                dry_run=False,
                initial_bootstrap=True,
            )
            self.assertFalse(
                success,
                "Edge verification failure after initial bootstrap must fail without attempting invalid rollback",
            )

    # =========================================================================
    # 8. FRONTEND BUILD PREREQUISITE & READINESS PATH VALIDATION
    # =========================================================================

    @patch("switch_edge_traffic.verify_frontend_dist", return_value=False)
    @patch("switch_edge_traffic.subprocess.run")
    def test_missing_frontend_dist_refuses_deployment(self, mock_subprocess, mock_verify_dist):
        MockHealthServerHandler.backend_status = "UP"
        with patch.dict(os.environ, {"CLOUDFLARE_API_TOKEN": "mock_token"}):
            success = switch_edge_traffic(
                env="staging",
                worker_name="queueless-staging",
                new_backend_url=f"http://127.0.0.1:{self.port}",
                backend_health_url=f"http://127.0.0.1:{self.port}/actuator/health/readiness",
                edge_public_url=f"http://127.0.0.1:{self.port}",
                current_backend_url=None,
                dry_run=False,
                initial_bootstrap=True,
            )
            self.assertFalse(success, "Missing frontend/dist must fail closed before invoking wrangler deploy")
            mock_subprocess.assert_not_called()

    def test_db_readiness_component_down_fails_backend_health(self):
        MockHealthServerHandler.backend_status = "DB_READINESS_DOWN"
        healthy = verify_direct_backend(
            f"http://127.0.0.1:{self.port}/actuator/health/readiness", timeout_seconds=2, interval_seconds=1
        )
        self.assertFalse(
            healthy, "Backend health must fail if dbReadiness component is DOWN even if overall status is UP"
        )


if __name__ == "__main__":
    unittest.main()
