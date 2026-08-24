"""Non-secret fixture shared by invite-production feature assertion tests."""

from __future__ import annotations

import importlib.util
from pathlib import Path


ASSERT = Path(__file__).with_name("assert-invite-production-feature-config.py")
SPEC = importlib.util.spec_from_file_location("invite_feature_assertion", ASSERT)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def valid_model() -> dict:
    return {
        "services": {
            "parking-service": {
                "environment": {
                    **MODULE.PARKING_EXPECTED,
                    "DATABASE_PASSWORD": "must-never-appear-in-evidence",
                }
            },
            "web": {"build": {"args": dict(MODULE.WEB_EXPECTED)}},
        }
    }
