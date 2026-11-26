#!/usr/bin/env python3
"""
QueueLess — Backend Provider Adapters
=============================================================================
Encapsulates provider-specific deployment mechanics, configuration validation,
command/webhook generation, and health readiness verification for:
1. Render (Webhooks / Deploy API)
2. Google Cloud Run (Managed Serverless Container Deployment)

AWS and Kubernetes are explicitly non-operational in v1.
=============================================================================
"""

import json
import os
import subprocess
import sys
import urllib.error
import urllib.request
from abc import ABC, abstractmethod
from typing import Any

sys.path.insert(0, os.path.dirname(__file__))
from verify_backend_health import verify_direct_backend

OPERATIONAL_PROVIDERS_V1 = {"render", "cloud_run", "local"}
NON_OPERATIONAL_PROVIDERS = {"aws", "kubernetes", "ecs", "app_runner"}


class ProviderAdapter(ABC):
    """Abstract base class for backend deployment provider adapters."""

    def __init__(self, name: str):
        self.name = name

    @abstractmethod
    def validate_configuration(self, config: dict[str, Any]) -> list[str]:
        """Validates provider configuration dictionary. Returns list of error messages."""

    @abstractmethod
    def build_deployment_payload(self, config: dict[str, Any], **kwargs) -> dict[str, Any]:
        """Builds the deployment command, webhook payload, or API request details."""

    @abstractmethod
    def deploy(self, config: dict[str, Any], dry_run: bool = False, **kwargs) -> dict[str, Any]:
        """Executes or simulates provider deployment."""

    def verify_readiness(self, config: dict[str, Any], timeout_seconds: int = 120) -> bool:
        """Polls the backend health readiness endpoint."""
        backend_url = config.get("backend_url", "").rstrip("/")
        health_path = config.get("health_check_path", "/actuator/health")
        if not health_path.startswith("/"):
            health_path = "/" + health_path
        health_url = f"{backend_url}{health_path}"
        return verify_direct_backend(health_url, timeout_seconds=timeout_seconds)


class RenderProviderAdapter(ProviderAdapter):
    """Provider adapter for Render deployment webhooks and service validation."""

    def __init__(self):
        super().__init__("render")

    def validate_configuration(self, config: dict[str, Any]) -> list[str]:
        errors = []
        required = ["service_name", "backend_url", "region", "plan", "deploy_hook_secret_key", "health_check_path"]
        for field in required:
            if not config.get(field):
                errors.append(f"Missing required Render field: '{field}'")
        return errors

    def build_deployment_payload(self, config: dict[str, Any], **kwargs) -> dict[str, Any]:
        hook_secret_key = config.get("deploy_hook_secret_key", "RENDER_DEPLOY_HOOK_URL")
        hook_url = kwargs.get("deploy_hook_url") or os.environ.get(hook_secret_key, "")
        return {
            "provider": "render",
            "service_name": config.get("service_name"),
            "backend_url": config.get("backend_url"),
            "region": config.get("region"),
            "plan": config.get("plan"),
            "deploy_hook_secret_key": hook_secret_key,
            "deploy_hook_url_configured": bool(hook_url and hook_url.strip()),
            "method": "POST",
            "target": hook_url if hook_url else f"${{{hook_secret_key}}}",
        }

    def deploy(self, config: dict[str, Any], dry_run: bool = False, **kwargs) -> dict[str, Any]:
        payload = self.build_deployment_payload(config, **kwargs)
        if dry_run:
            return {
                "success": True,
                "mode": "DRY_RUN",
                "provider": "render",
                "message": f"Simulated Render deployment webhook trigger for service '{config.get('service_name')}'.",
                "payload": payload,
            }

        hook_secret_key = config.get("deploy_hook_secret_key", "RENDER_DEPLOY_HOOK_URL")
        hook_url = kwargs.get("deploy_hook_url") or os.environ.get(hook_secret_key, "")
        if not hook_url or not hook_url.strip():
            return {
                "success": False,
                "mode": "LIVE",
                "provider": "render",
                "error": f"Missing deploy hook URL! Environment variable '{hook_secret_key}' is not set.",
            }

        try:
            req = urllib.request.Request(hook_url, method="POST", headers={"User-Agent": "QueueLess-Deployer/1.0"})
            with urllib.request.urlopen(req, timeout=15) as resp:
                status = resp.getcode()
                body = resp.read().decode("utf-8", errors="replace")
                return {
                    "success": 200 <= status < 300,
                    "mode": "LIVE",
                    "http_status": status,
                    "body": body[:500],
                    "provider": "render",
                }
        except Exception as ex:
            return {"success": False, "mode": "LIVE", "error": str(ex), "provider": "render"}


class CloudRunProviderAdapter(ProviderAdapter):
    """Provider adapter for Google Cloud Run managed service deployment."""

    def __init__(self):
        super().__init__("cloud_run")

    def validate_configuration(self, config: dict[str, Any]) -> list[str]:
        errors = []
        required = [
            "service_name",
            "backend_url",
            "region",
            "min_instances",
            "max_instances",
            "cpu",
            "memory",
            "service_account_secret_key",
            "health_check_path",
        ]
        for field in required:
            if config.get(field) is None or config.get(field) == "":
                errors.append(f"Missing required Cloud Run field: '{field}'")
        return errors

    def build_deployment_payload(self, config: dict[str, Any], **kwargs) -> dict[str, Any]:
        image_uri = kwargs.get("image_uri", "asia-south1-docker.pkg.dev/PROJECT/REPO/backend:latest")
        service_name = config.get("service_name", "queueless-backend")
        region = config.get("region", "asia-south1")
        min_instances = str(config.get("min_instances", 0))
        max_instances = str(config.get("max_instances", 1))
        cpu = str(config.get("cpu", "1"))
        memory = str(config.get("memory", "1Gi"))

        cmd = [
            "gcloud",
            "run",
            "deploy",
            service_name,
            f"--image={image_uri}",
            f"--region={region}",
            "--platform=managed",
            f"--min-instances={min_instances}",
            f"--max-instances={max_instances}",
            f"--cpu={cpu}",
            f"--memory={memory}",
        ]

        service_account = kwargs.get("service_account")
        if service_account:
            cmd.append(f"--service-account={service_account}")

        env_vars = kwargs.get("env_vars")
        if env_vars:
            cmd.append(f"--set-env-vars={env_vars}")

        secrets = kwargs.get("secrets")
        if secrets:
            cmd.append(f"--set-secrets={secrets}")

        return {
            "provider": "cloud_run",
            "service_name": service_name,
            "region": region,
            "image_uri": image_uri,
            "command": cmd,
            "command_string": " ".join(cmd),
        }

    def deploy(self, config: dict[str, Any], dry_run: bool = False, **kwargs) -> dict[str, Any]:
        payload = self.build_deployment_payload(config, **kwargs)
        if dry_run:
            return {
                "success": True,
                "mode": "DRY_RUN",
                "provider": "cloud_run",
                "message": f"Simulated Cloud Run deployment command generation for service '{config.get('service_name')}'.",
                "command": payload["command_string"],
            }

        try:
            res = subprocess.run(payload["command"], capture_output=True, text=True, check=True)
            return {"success": True, "mode": "LIVE", "provider": "cloud_run", "stdout": res.stdout[:500]}
        except Exception as ex:
            return {"success": False, "mode": "LIVE", "provider": "cloud_run", "error": str(ex)}


class LocalProviderAdapter(ProviderAdapter):
    """Provider adapter for local developer test backend."""

    def __init__(self):
        super().__init__("local")

    def validate_configuration(self, config: dict[str, Any]) -> list[str]:
        errors = []
        for fld in ["service_name", "backend_url", "health_check_path"]:
            if not config.get(fld):
                errors.append(f"Missing required local field: '{fld}'")
        return errors

    def build_deployment_payload(self, config: dict[str, Any], **kwargs) -> dict[str, Any]:
        return {
            "provider": "local",
            "service_name": config.get("service_name"),
            "backend_url": config.get("backend_url"),
            "command": "mvn spring-boot:run",
        }

    def deploy(self, config: dict[str, Any], dry_run: bool = False, **kwargs) -> dict[str, Any]:
        return {
            "success": True,
            "mode": "DRY_RUN" if dry_run else "LIVE",
            "provider": "local",
            "message": "Local developer backend runs in-process or via IDE.",
        }


def get_provider_adapter(provider_name: str) -> ProviderAdapter:
    """
    Factory function returning the operational provider adapter.
    Explicitly rejects AWS and Kubernetes as non-operational in v1.
    """
    norm_name = str(provider_name).strip().lower()

    if norm_name in NON_OPERATIONAL_PROVIDERS:
        raise ValueError(
            f"Provider '{provider_name}' is not operational in v1! "
            f"Only operational adapters are supported: {sorted(OPERATIONAL_PROVIDERS_V1)}. "
            f"AWS and Kubernetes adapters must not be operational until implemented and tested."
        )

    if norm_name == "render":
        return RenderProviderAdapter()
    elif norm_name == "cloud_run":
        return CloudRunProviderAdapter()
    elif norm_name == "local":
        return LocalProviderAdapter()
    else:
        raise ValueError(
            f"Unknown or unsupported provider '{provider_name}'. "
            f"Supported providers: {sorted(OPERATIONAL_PROVIDERS_V1)}"
        )


def main():
    import argparse

    import yaml

    parser = argparse.ArgumentParser(description="QueueLess Provider Deployment Adapter CLI")
    parser.add_argument("--provider", required=True, help="Provider name (render, cloud_run, local)")
    parser.add_argument("--config", help="Path to environment YAML configuration file")
    parser.add_argument(
        "--action", choices=["validate", "payload", "deploy"], default="deploy", help="Action to execute"
    )
    parser.add_argument("--image-uri", help="Container image URI for Cloud Run")
    parser.add_argument("--service-account", help="GCP service account email for Cloud Run")
    parser.add_argument("--env-vars", help="Environment variables for Cloud Run")
    parser.add_argument("--secrets", help="Secrets for Cloud Run")
    parser.add_argument("--hook-url", help="Deploy hook URL for Render")
    parser.add_argument("--dry-run", action="store_true", help="Simulate deployment without modifying infrastructure")
    args = parser.parse_args()

    try:
        adapter = get_provider_adapter(args.provider)
    except ValueError as e:
        print(f"FATAL: {e}", file=sys.stderr)
        sys.exit(1)

    prov_config: dict[str, Any] = {}
    if args.config:
        with open(args.config, encoding="utf-8") as f:
            full_config = yaml.safe_load(f) or {}
            prov_config = full_config.get("providers", {}).get(args.provider, {})

    kwargs: dict[str, Any] = {}
    if args.image_uri:
        kwargs["image_uri"] = args.image_uri
    if args.service_account:
        kwargs["service_account"] = args.service_account
    if args.env_vars:
        kwargs["env_vars"] = args.env_vars
    if args.secrets:
        kwargs["secrets"] = args.secrets
    if args.hook_url:
        kwargs["deploy_hook_url"] = args.hook_url

    if args.action == "validate":
        errs = adapter.validate_configuration(prov_config)
        if errs:
            print(f"Validation FAILED for {args.provider}:", file=sys.stderr)
            for err in errs:
                print(f"  - {err}", file=sys.stderr)
            sys.exit(1)
        print(f"Configuration valid for provider '{args.provider}'.")
        sys.exit(0)

    elif args.action == "payload":
        payload = adapter.build_deployment_payload(prov_config, **kwargs)
        print(json.dumps(payload, indent=2))
        sys.exit(0)

    elif args.action == "deploy":
        result = adapter.deploy(prov_config, dry_run=args.dry_run, **kwargs)
        print(json.dumps(result, indent=2))
        if not result.get("success", False):
            sys.exit(1)
        sys.exit(0)


if __name__ == "__main__":
    main()
