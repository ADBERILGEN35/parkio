#!/usr/bin/env python3
"""Materialize the production env file from Azure Key Vault without logging values."""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
import tempfile
from pathlib import Path


RESOURCE_GROUP = "rg-parkio-invite-production-we"
FOUNDATION_DEPLOYMENT = "prod-deploy-01a-foundation"

SECRET_KEYS = {
    "PARKIO_JWT_PRIVATE_KEY_PEM": "jwt-private-key-pem",
    "PARKIO_GATEWAY_INTERNAL_SECRET": "gateway-internal-secret",
    "PARKIO_WAITLIST_HASH_SECRET": "waitlist-hash-secret",
    "POSTGRES_AUTH_PASSWORD": "postgres-auth-runtime-password",
    "POSTGRES_AUTH_MIGRATION_PASSWORD": "postgres-auth-migration-password",
    "POSTGRES_GATEWAY_PASSWORD": "postgres-gateway-runtime-password",
    "POSTGRES_GATEWAY_MIGRATION_PASSWORD": "postgres-gateway-migration-password",
    "POSTGRES_USER_PASSWORD": "postgres-user-runtime-password",
    "POSTGRES_USER_MIGRATION_PASSWORD": "postgres-user-migration-password",
    "POSTGRES_PARKING_PASSWORD": "postgres-parking-runtime-password",
    "POSTGRES_PARKING_MIGRATION_PASSWORD": "postgres-parking-migration-password",
    "POSTGRES_MEDIA_PASSWORD": "postgres-media-runtime-password",
    "POSTGRES_MEDIA_MIGRATION_PASSWORD": "postgres-media-migration-password",
    "POSTGRES_GAMIFICATION_PASSWORD": "postgres-gamification-runtime-password",
    "POSTGRES_GAMIFICATION_MIGRATION_PASSWORD": "postgres-gamification-migration-password",
    "POSTGRES_NOTIFICATION_PASSWORD": "postgres-notification-runtime-password",
    "POSTGRES_NOTIFICATION_MIGRATION_PASSWORD": "postgres-notification-migration-password",
    "POSTGRES_MODERATION_PASSWORD": "postgres-moderation-runtime-password",
    "POSTGRES_MODERATION_MIGRATION_PASSWORD": "postgres-moderation-migration-password",
    "POSTGRES_ANALYTICS_PASSWORD": "postgres-analytics-runtime-password",
    "POSTGRES_ANALYTICS_MIGRATION_PASSWORD": "postgres-analytics-migration-password",
    "POSTGRES_AIVALIDATION_PASSWORD": "postgres-aivalidation-runtime-password",
    "POSTGRES_AIVALIDATION_MIGRATION_PASSWORD": "postgres-aivalidation-migration-password",
    "REDIS_PASSWORD": "redis-password",
    "KAFKA_CLUSTER_ID": "kafka-cluster-id",
    "MINIO_ROOT_PASSWORD": "minio-root-password",
    "GRAFANA_ADMIN_PASSWORD": "grafana-admin-password",
    "BACKUP_ENCRYPT_PASSPHRASE": "backup-encryption-passphrase",
    # Operator-provided third-party/contact values. These are required and are
    # never substituted with fake production values.
    "PARKIO_ACME_EMAIL": "acme-contact-email",
    "VITE_MAPTILER_KEY": "maptiler-public-key",
    "PARKIO_RESEND_API_KEY": "resend-api-key",
    "PARKIO_EXPO_ACCESS_TOKEN": "expo-access-token",
    "PARKIO_ALERT_SLACK_WEBHOOK_URL": "slack-webhook-url",
}


def az(*args: str, allow_failure: bool = False) -> str:
    result = subprocess.run(
        ["az", *args],
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        text=True,
    )
    if result.returncode and not allow_failure:
        raise RuntimeError(f"Azure CLI command failed for non-secret operation: {args[0]}")
    return result.stdout.rstrip("\r\n") if result.returncode == 0 else ""


def dotenv_value(value: str) -> str:
    escaped = value.replace("\\", "\\\\").replace("\r", "").replace("\n", "\\n").replace('"', '\\"')
    return f'"{escaped}"'


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--template", default="docker/.env.invite-production.example")
    parser.add_argument("--output", default="docker/.env.invite-production")
    args = parser.parse_args()

    template = Path(args.template).resolve()
    output = Path(args.output).resolve()
    if not template.is_file():
        print(f"ERROR: env template not found: {template}", file=sys.stderr)
        return 2
    if output == template:
        print("ERROR: refusing to overwrite the checked-in env template.", file=sys.stderr)
        return 2

    az("login", "--identity", "--allow-no-subscriptions", "--output", "none")
    outputs_query = "properties.outputs"
    key_vault = az(
        "deployment", "group", "show", "--resource-group", RESOURCE_GROUP,
        "--name", FOUNDATION_DEPLOYMENT, "--query", f"{outputs_query}.keyVaultName.value", "--output", "tsv",
    )
    postgres_host = az(
        "deployment", "group", "show", "--resource-group", RESOURCE_GROUP,
        "--name", FOUNDATION_DEPLOYMENT, "--query", f"{outputs_query}.postgresqlFqdn.value", "--output", "tsv",
    )
    backup_account = az(
        "deployment", "group", "show", "--resource-group", RESOURCE_GROUP,
        "--name", FOUNDATION_DEPLOYMENT, "--query", f"{outputs_query}.backupStorageAccount.value", "--output", "tsv",
    )
    backup_container = az(
        "deployment", "group", "show", "--resource-group", RESOURCE_GROUP,
        "--name", FOUNDATION_DEPLOYMENT, "--query", f"{outputs_query}.backupContainer.value", "--output", "tsv",
    )

    replacements = {
        "PARKIO_PG_HOST": postgres_host,
        "BACKUP_AZURE_STORAGE_ACCOUNT": backup_account,
        "BACKUP_AZURE_CONTAINER": backup_container,
    }
    missing: list[str] = []
    for env_key, secret_name in SECRET_KEYS.items():
        value = az(
            "keyvault", "secret", "show", "--vault-name", key_vault,
            "--name", secret_name, "--query", "value", "--output", "tsv",
            allow_failure=True,
        )
        if not value:
            missing.append(secret_name)
        else:
            replacements[env_key] = value

    if missing:
        print("ERROR: required Key Vault secret names are missing:", file=sys.stderr)
        for name in missing:
            print(f"  - {name}", file=sys.stderr)
        print("No env file was written.", file=sys.stderr)
        return 3

    lines: list[str] = []
    seen: set[str] = set()
    for line in template.read_text().splitlines():
        if not line or line.lstrip().startswith("#") or "=" not in line:
            lines.append(line)
            continue
        key, _ = line.split("=", 1)
        if key in replacements:
            lines.append(f"{key}={dotenv_value(replacements[key])}")
            seen.add(key)
        else:
            lines.append(line)

    absent_keys = sorted(set(replacements) - seen)
    if absent_keys:
        print(f"ERROR: template is missing required keys: {', '.join(absent_keys)}", file=sys.stderr)
        return 3

    output.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary_name = tempfile.mkstemp(prefix=f".{output.name}.", dir=output.parent)
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, "w") as handle:
            handle.write("\n".join(lines) + "\n")
        os.replace(temporary_name, output)
    finally:
        if os.path.exists(temporary_name):
            os.unlink(temporary_name)

    print(f"Invite-production env materialized at {output} (mode 0600; values suppressed).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
