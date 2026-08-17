#!/usr/bin/env python3
"""Regression test for the invite-production Key Vault secret contract."""

from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path


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


if __name__ == "__main__":
    unittest.main()
