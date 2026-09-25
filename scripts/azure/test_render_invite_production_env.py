#!/usr/bin/env python3
"""Regression test for the invite-production Key Vault secret contract."""

from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


MODULE_PATH = Path(__file__).with_name("render-invite-production-env.py")


def load_renderer():
    spec = importlib.util.spec_from_file_location("invite_production_renderer", MODULE_PATH)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load renderer: {MODULE_PATH}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class InviteProductionSecretContractTest(unittest.TestCase):
    def test_operator_secret_set_is_exact_and_excludes_mobile(self) -> None:
        renderer = load_renderer()
        self.assertEqual(
            set(renderer.OPERATOR_SECRET_KEYS.values()),
            {
                "acme-contact-email",
                "maptiler-public-key",
                "resend-api-key",
                "slack-webhook-url",
            },
        )
        self.assertNotIn("expo-access-token", renderer.SECRET_KEYS.values())
        self.assertNotIn("PARKIO_EXPO_ACCESS_TOKEN", renderer.SECRET_KEYS)

    def test_missing_key_vault_secret_fails_without_writing_output(self) -> None:
        renderer = load_renderer()
        missing_name = "resend-api-key"

        def fake_az(*args: str, allow_failure: bool = False) -> str:
            del allow_failure
            if args[:2] == ("login", "--identity"):
                return ""
            if args[:3] == ("deployment", "group", "show"):
                query = args[args.index("--query") + 1]
                if "keyVaultName" in query:
                    return "kv-test"
                if "postgresqlFqdn" in query:
                    return "pg-test.postgres.database.azure.com"
                if "backupStorageAccount" in query:
                    return "sttest"
                if "backupContainer" in query:
                    return "invite-production-backups"
            if args[:3] == ("keyvault", "secret", "show"):
                name = args[args.index("--name") + 1]
                return "" if name == missing_name else f"fixture-{name}-value"
            raise AssertionError(f"unexpected az call: {args}")

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            template = root / "template.env"
            output = root / "rendered.env"
            keys = [
                "PARKIO_PG_HOST",
                "BACKUP_AZURE_STORAGE_ACCOUNT",
                "BACKUP_AZURE_CONTAINER",
                *renderer.SECRET_KEYS.keys(),
            ]
            template.write_text("\n".join(f"{key}=fixture" for key in keys) + "\n")

            argv = [
                str(MODULE_PATH),
                "--template",
                str(template),
                "--output",
                str(output),
            ]
            with mock.patch.object(renderer, "az", side_effect=fake_az), mock.patch.object(
                sys, "argv", argv
            ):
                self.assertEqual(renderer.main(), 3)

            self.assertFalse(output.exists())

    def test_missing_key_vault_access_refuses_without_writing_output(self) -> None:
        renderer = load_renderer()

        def denied_az(*args: str, allow_failure: bool = False) -> str:
            del allow_failure
            if args[:2] == ("login", "--identity"):
                return ""
            if args[:3] == ("deployment", "group", "show"):
                query = args[args.index("--query") + 1]
                values = {
                    "keyVaultName": "kv-test",
                    "postgresqlFqdn": "pg-test.postgres.database.azure.com",
                    "backupStorageAccount": "sttest",
                    "backupContainer": "invite-production-backups",
                }
                return next(value for key, value in values.items() if key in query)
            if args[:3] == ("keyvault", "secret", "show"):
                raise RuntimeError("Key Vault access denied")
            raise AssertionError(f"unexpected az call: {args}")

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            template = root / "template.env"
            output = root / "rendered.env"
            keys = [
                "PARKIO_PG_HOST",
                "BACKUP_AZURE_STORAGE_ACCOUNT",
                "BACKUP_AZURE_CONTAINER",
                *renderer.SECRET_KEYS.keys(),
            ]
            template.write_text("\n".join(f"{key}=fixture" for key in keys) + "\n")
            argv = [
                str(MODULE_PATH),
                "--template",
                str(template),
                "--output",
                str(output),
            ]
            with mock.patch.object(renderer, "az", side_effect=denied_az), mock.patch.object(
                sys, "argv", argv
            ):
                with self.assertRaisesRegex(RuntimeError, "Key Vault access denied"):
                    renderer.main()
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
