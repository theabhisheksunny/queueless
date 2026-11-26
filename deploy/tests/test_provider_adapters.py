#!/usr/bin/env python3
"""
QueueLess — Provider Adapters & Traffic Switch Safety Unit Tests
=============================================================================
Tests Render and Cloud Run provider adapters, validates rejection of
non-operational providers (AWS, Kubernetes), and verifies strict
production traffic switch approval enforcement.
=============================================================================
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "scripts"))
from provider_adapters import (
    CloudRunProviderAdapter,
    LocalProviderAdapter,
    RenderProviderAdapter,
    get_provider_adapter,
)
from switch_edge_traffic import switch_edge_traffic


class TestProviderAdapters(unittest.TestCase):
    def test_operational_provider_resolution(self):
        render = get_provider_adapter("render")
        self.assertIsInstance(render, RenderProviderAdapter)
        self.assertEqual(render.name, "render")

        cloud_run = get_provider_adapter("cloud_run")
        self.assertIsInstance(cloud_run, CloudRunProviderAdapter)
        self.assertEqual(cloud_run.name, "cloud_run")

        local = get_provider_adapter("local")
        self.assertIsInstance(local, LocalProviderAdapter)
        self.assertEqual(local.name, "local")

    def test_non_operational_providers_strictly_rejected(self):
        for non_op in ["aws", "kubernetes", "ecs", "app_runner"]:
            with self.assertRaises(ValueError) as ctx:
                get_provider_adapter(non_op)
            self.assertIn("not operational in v1", str(ctx.exception))

    def test_unknown_provider_rejected(self):
        with self.assertRaises(ValueError) as ctx:
            get_provider_adapter("azure_container_apps")
        self.assertIn("Unknown or unsupported provider", str(ctx.exception))

    def test_render_adapter_validation(self):
        adapter = RenderProviderAdapter()
        valid_cfg = {
            "service_name": "queueless-backend-staging",
            "backend_url": "https://queueless-backend-staging.onrender.com",
            "region": "singapore",
            "plan": "free",
            "deploy_hook_secret_key": "RENDER_STAGING_DEPLOY_HOOK_URL",
            "health_check_path": "/actuator/health",
        }
        self.assertEqual(adapter.validate_configuration(valid_cfg), [])

        invalid_cfg = {"service_name": "test"}
        errors = adapter.validate_configuration(invalid_cfg)
        self.assertGreaterEqual(len(errors), 4)

    def test_render_adapter_dry_run_deploy(self):
        adapter = RenderProviderAdapter()
        cfg = {
            "service_name": "queueless-backend-staging",
            "backend_url": "https://queueless-backend-staging.onrender.com",
            "region": "singapore",
            "plan": "free",
            "deploy_hook_secret_key": "RENDER_STAGING_DEPLOY_HOOK_URL",
            "health_check_path": "/actuator/health",
        }
        res = adapter.deploy(cfg, dry_run=True)
        self.assertTrue(res["success"])
        self.assertEqual(res["mode"], "DRY_RUN")
        self.assertEqual(res["provider"], "render")

    def test_cloud_run_adapter_validation(self):
        adapter = CloudRunProviderAdapter()
        valid_cfg = {
            "service_name": "queueless-backend",
            "backend_url": "https://queueless-backend-1043223278240.asia-south1.run.app",
            "region": "asia-south1",
            "min_instances": 0,
            "max_instances": 3,
            "cpu": "1",
            "memory": "1Gi",
            "service_account_secret_key": "GCP_PROD_DEPLOYER_SA",
            "health_check_path": "/actuator/health",
        }
        self.assertEqual(adapter.validate_configuration(valid_cfg), [])

        invalid_cfg = {"service_name": "test"}
        errors = adapter.validate_configuration(invalid_cfg)
        self.assertGreaterEqual(len(errors), 7)

    def test_cloud_run_adapter_command_generation_and_dry_run(self):
        adapter = CloudRunProviderAdapter()
        cfg = {
            "service_name": "queueless-backend",
            "backend_url": "https://queueless-backend-1043223278240.asia-south1.run.app",
            "region": "asia-south1",
            "min_instances": 0,
            "max_instances": 3,
            "cpu": "1",
            "memory": "1Gi",
            "service_account_secret_key": "GCP_PROD_DEPLOYER_SA",
            "health_check_path": "/actuator/health",
        }
        payload = adapter.build_deployment_payload(
            cfg,
            image_uri="asia-south1-docker.pkg.dev/myproj/repo:tag",
            service_account="prod-sa@myproj.iam.gserviceaccount.com",
            env_vars="SPRING_PROFILES_ACTIVE=prod",
            secrets="APP_JWT_SECRET=secret:latest",
        )
        self.assertIn("gcloud run deploy queueless-backend", payload["command_string"])
        self.assertIn("--image=asia-south1-docker.pkg.dev/myproj/repo:tag", payload["command_string"])
        self.assertIn("--region=asia-south1", payload["command_string"])
        self.assertIn("--service-account=prod-sa@myproj.iam.gserviceaccount.com", payload["command_string"])
        self.assertIn("--set-env-vars=SPRING_PROFILES_ACTIVE=prod", payload["command_string"])
        self.assertIn("--set-secrets=APP_JWT_SECRET=secret:latest", payload["command_string"])

        res = adapter.deploy(cfg, dry_run=True, image_uri="asia-south1-docker.pkg.dev/myproj/repo:tag")
        self.assertTrue(res["success"])
        self.assertEqual(res["mode"], "DRY_RUN")
        self.assertEqual(res["provider"], "cloud_run")

    def test_provider_adapter_cli_interface(self):
        import subprocess

        script = os.path.join(os.path.dirname(__file__), "..", "scripts", "provider_adapters.py")
        prod_yaml = os.path.join(os.path.dirname(__file__), "..", "config", "production.yaml")

        # 1. Test validate action
        res = subprocess.run(
            [sys.executable, script, "--provider", "cloud_run", "--config", prod_yaml, "--action", "validate"],
            capture_output=True,
            text=True,
        )
        self.assertEqual(res.returncode, 0, f"CLI validate failed: {res.stderr}")
        self.assertIn("Configuration valid", res.stdout)

        # 2. Test payload generation action
        res = subprocess.run(
            [
                sys.executable,
                script,
                "--provider",
                "cloud_run",
                "--config",
                prod_yaml,
                "--action",
                "payload",
                "--image-uri",
                "asia-south1-docker.pkg.dev/test:tag",
            ],
            capture_output=True,
            text=True,
        )
        self.assertEqual(res.returncode, 0, f"CLI payload failed: {res.stderr}")
        self.assertIn("gcloud run deploy queueless-backend", res.stdout)

        # 3. Test non-operational provider rejection via CLI
        res = subprocess.run(
            [sys.executable, script, "--provider", "aws", "--config", prod_yaml, "--action", "validate"],
            capture_output=True,
            text=True,
        )
        self.assertNotEqual(res.returncode, 0)
        self.assertIn("not operational in v1", res.stderr)

    def test_production_traffic_switch_requires_explicit_approval(self):
        # In live mode (dry_run=False), production switch without confirm_production must fail closed
        success = switch_edge_traffic(
            env="production",
            worker_name="queueless",
            new_backend_url="https://queueless-backend-1043223278240.asia-south1.run.app",
            backend_health_url="https://queueless-backend-1043223278240.asia-south1.run.app/actuator/health",
            edge_public_url="https://queueless.theabhisheksunny.workers.dev",
            dry_run=False,
            confirm_production=False,
        )
        self.assertFalse(success, "Production traffic switch must fail closed without explicit approval")

    def test_production_dry_run_proceeds_without_confirmation(self):
        # In dry_run mode, simulation proceeds to demonstrate intended commands
        success = switch_edge_traffic(
            env="production",
            worker_name="queueless",
            new_backend_url="https://queueless-backend-1043223278240.asia-south1.run.app",
            backend_health_url="https://queueless-backend-1043223278240.asia-south1.run.app/actuator/health",
            edge_public_url="https://queueless.theabhisheksunny.workers.dev",
            dry_run=True,
            initial_bootstrap=True,
            confirm_production=False,
        )
        self.assertTrue(success, "Production dry-run simulation should succeed to inspect planned operations")


if __name__ == "__main__":
    unittest.main()
