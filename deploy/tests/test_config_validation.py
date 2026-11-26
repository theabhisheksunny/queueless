#!/usr/bin/env python3
"""
QueueLess — Automated Deployment Configuration Test Suite
Tests positive baseline configurations and comprehensive negative adversarial cases.
"""

import os
import sys
import tempfile
import unittest

import yaml

# Add scripts directory to path
SCRIPTS_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "scripts"))
CONFIG_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "config"))
sys.path.insert(0, SCRIPTS_DIR)

from resolve_deployment_target import resolve_configuration
from validate_config import validate_file


class TestDeploymentConfigurationValidation(unittest.TestCase):
    def setUp(self):
        self.local_yaml = os.path.join(CONFIG_DIR, "local.yaml")
        self.staging_yaml = os.path.join(CONFIG_DIR, "staging.yaml")
        self.production_yaml = os.path.join(CONFIG_DIR, "production.yaml")

    def _write_temp_yaml(self, data: dict) -> str:
        temp_file = tempfile.NamedTemporaryFile(mode="w", suffix=".yaml", delete=False, encoding="utf-8")
        yaml.safe_dump(data, temp_file)
        temp_file.close()
        return temp_file.name

    # =========================================================================
    # POSITIVE BASELINE TESTS
    # =========================================================================

    def test_positive_local_configuration(self):
        errors = validate_file(self.local_yaml)
        self.assertEqual(errors, [], f"local.yaml should be valid, got: {errors}")
        resolved = resolve_configuration(self.local_yaml)
        self.assertEqual(resolved["ENVIRONMENT"], "local")
        self.assertEqual(resolved["ACTIVE_PROVIDER"], "local")
        self.assertEqual(resolved["SPRING_PROFILE"], "local")
        self.assertEqual(resolved["BACKEND_URL"], "http://localhost:8080")

    def test_positive_staging_configuration(self):
        errors = validate_file(self.staging_yaml)
        self.assertEqual(errors, [], f"staging.yaml should be valid, got: {errors}")
        resolved = resolve_configuration(self.staging_yaml)
        self.assertEqual(resolved["ENVIRONMENT"], "staging")
        self.assertEqual(resolved["ACTIVE_PROVIDER"], "render")
        self.assertEqual(resolved["SPRING_PROFILE"], "staging")
        self.assertEqual(resolved["BACKEND_URL"], "https://queueless-backend-staging.onrender.com")
        self.assertEqual(resolved["EDGE_PUBLIC_URL"], "https://queueless-staging.theabhisheksunny.workers.dev")
        self.assertEqual(resolved["DB_POOLER_PORT"], 5432)

    def test_positive_production_configuration(self):
        errors = validate_file(self.production_yaml)
        self.assertEqual(errors, [], f"production.yaml should be valid, got: {errors}")
        resolved = resolve_configuration(self.production_yaml)
        self.assertEqual(resolved["ENVIRONMENT"], "production")
        self.assertEqual(resolved["ACTIVE_PROVIDER"], "render")
        self.assertEqual(resolved["SPRING_PROFILE"], "prod")
        self.assertEqual(resolved["BACKEND_URL"], "https://queueless-backend.onrender.com")
        self.assertEqual(resolved["EDGE_PUBLIC_URL"], "https://queueless.theabhisheksunny.workers.dev")
        self.assertEqual(resolved["DB_POOLER_PORT"], 5432)

    # =========================================================================
    # NEGATIVE ADVERSARIAL TESTS
    # =========================================================================

    def test_negative_aws_not_operational_in_v1(self):
        with open(self.staging_yaml, encoding="utf-8") as f:
            data = yaml.safe_load(f)
        data["active_provider"] = "aws"
        temp_path = self._write_temp_yaml(data)
        try:
            errors = validate_file(temp_path)
            self.assertTrue(
                any("AWS provider is stubbed for future extension and not yet operational in v1" in e for e in errors),
                f"Expected AWS rejection error, got: {errors}",
            )
        finally:
            os.remove(temp_path)

    def test_negative_invalid_active_provider(self):
        with open(self.staging_yaml, encoding="utf-8") as f:
            data = yaml.safe_load(f)
        data["active_provider"] = "azure"
        temp_path = self._write_temp_yaml(data)
        try:
            errors = validate_file(temp_path)
            self.assertTrue(
                any("Invalid active_provider 'azure'" in e for e in errors),
                f"Expected invalid provider error, got: {errors}",
            )
        finally:
            os.remove(temp_path)

    def test_negative_missing_active_provider_block(self):
        with open(self.staging_yaml, encoding="utf-8") as f:
            data = yaml.safe_load(f)
        del data["providers"]["render"]
        temp_path = self._write_temp_yaml(data)
        try:
            errors = validate_file(temp_path)
            self.assertTrue(
                any("Provider configuration block 'providers.render' is missing" in e for e in errors),
                f"Expected missing provider block error, got: {errors}",
            )
        finally:
            os.remove(temp_path)

    def test_negative_missing_required_provider_field(self):
        with open(self.staging_yaml, encoding="utf-8") as f:
            data = yaml.safe_load(f)
        del data["providers"]["render"]["deploy_hook_secret_key"]
        temp_path = self._write_temp_yaml(data)
        try:
            errors = validate_file(temp_path)
            self.assertTrue(
                any("Missing required field 'providers.render.deploy_hook_secret_key'" in e for e in errors),
                f"Expected missing deploy hook error, got: {errors}",
            )
        finally:
            os.remove(temp_path)

    def test_negative_plaintext_secret_leakage(self):
        with open(self.staging_yaml, encoding="utf-8") as f:
            data = yaml.safe_load(f)
        data["providers"]["render"]["deploy_hook_secret_key"] = "https://api.render.com/deploy/srv-12345?key=Secret!123"
        temp_path = self._write_temp_yaml(data)
        try:
            errors = validate_file(temp_path)
            self.assertTrue(
                any("Secret reference" in e and "must be an uppercase alphanumeric" in e for e in errors),
                f"Expected secret leakage error, got: {errors}",
            )
        finally:
            os.remove(temp_path)

    def test_negative_invalid_database_port(self):
        with open(self.staging_yaml, encoding="utf-8") as f:
            data = yaml.safe_load(f)
        data["database"]["pooler_port"] = 6543  # Transaction pooler forbidden
        temp_path = self._write_temp_yaml(data)
        try:
            errors = validate_file(temp_path)
            self.assertTrue(
                any("Database port must strictly be 5432" in e for e in errors),
                f"Expected pooler port 5432 error, got: {errors}",
            )
        finally:
            os.remove(temp_path)

    def test_negative_invalid_edge_health_path(self):
        with open(self.staging_yaml, encoding="utf-8") as f:
            data = yaml.safe_load(f)
        data["edge"]["health_check_path"] = "/actuator/health"  # Worker does not proxy /actuator
        temp_path = self._write_temp_yaml(data)
        try:
            errors = validate_file(temp_path)
            self.assertTrue(
                any("edge.health_check_path must start with '/api/'" in e for e in errors),
                f"Expected edge health path /api/ error, got: {errors}",
            )
        finally:
            os.remove(temp_path)

    def test_negative_http_in_production(self):
        with open(self.production_yaml, encoding="utf-8") as f:
            data = yaml.safe_load(f)
        data["providers"]["cloud_run"]["backend_url"] = "http://insecure-backend.run.app"
        temp_path = self._write_temp_yaml(data)
        try:
            errors = validate_file(temp_path)
            self.assertTrue(
                any("requires https scheme" in e for e in errors), f"Expected https requirement error, got: {errors}"
            )
        finally:
            os.remove(temp_path)

    def test_negative_environment_profile_mismatch(self):
        with open(self.staging_yaml, encoding="utf-8") as f:
            data = yaml.safe_load(f)
        data["spring_profile"] = "prod"  # Mismatch for staging
        temp_path = self._write_temp_yaml(data)
        try:
            errors = validate_file(temp_path)
            self.assertTrue(
                any("spring_profile mismatch" in e for e in errors), f"Expected profile mismatch error, got: {errors}"
            )
        finally:
            os.remove(temp_path)


if __name__ == "__main__":
    unittest.main()
