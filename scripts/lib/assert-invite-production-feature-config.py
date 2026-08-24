#!/usr/bin/env python3
"""Validate and emit secret-free invite-production feature evidence.

The resolved Compose JSON is consumed from stdin and is never persisted. Output
contains only an allowlist of non-secret booleans/strategy values.
"""

from __future__ import annotations

import argparse
import json
import sys
from typing import Any


PARKING_EXPECTED = {
    "PARKIO_MUNICIPAL_ENABLED": "true",
    "PARKIO_MUNICIPAL_MANUAL_SYNC_ENABLED": "false",
    "PARKIO_MUNICIPAL_DISCOVERY_DUPLICATE_PRESENTATION_ENABLED": "true",
    "PARKIO_MUNICIPAL_IZUM_ENABLED": "true",
    "PARKIO_MUNICIPAL_IZUM_SCHEDULER_ENABLED": "true",
    "PARKIO_MUNICIPAL_ISPARK_ENABLED": "true",
    "PARKIO_MUNICIPAL_ISPARK_SCHEDULER_ENABLED": "true",
    "PARKIO_MUNICIPAL_ANPARK_ENABLED": "false",
    "PARKIO_MUNICIPAL_ANPARK_SCHEDULER_ENABLED": "false",
    "PARKIO_MUNICIPAL_KONYA_ENABLED": "false",
    "PARKIO_MUNICIPAL_KONYA_SCHEDULER_ENABLED": "false",
    "PARKIO_MUNICIPAL_KAYSERI_ENABLED": "false",
    "PARKIO_MUNICIPAL_KAYSERI_SCHEDULER_ENABLED": "false",
    "PARKIO_MUNICIPAL_OSM_IMPORT_ENABLED": "false",
    "PARKIO_MUNICIPAL_OSM_SCHEDULER_ENABLED": "false",
    "PARKIO_MUNICIPAL_OSM_PUBLICATION_ENABLED": "false",
    "PARKIO_SPA_RECOMMENDATIONS_ENABLED": "true",
    "PARKIO_SPA_RANKING_ENABLED": "true",
    "PARKIO_SPA_RANKING_STRATEGY": "DETERMINISTIC_V1",
    "PARKIO_SPA_RANKING_SHADOW_ENABLED": "false",
    "PARKIO_SPA_RANKING_SHADOW_SAMPLE_RATE": "0.0",
    "PARKIO_SPA_RANKING_EVALUATION_ENABLED": "false",
    "PARKIO_SPA_RANKING_EVALUATION_ROLLUP_ENABLED": "false",
}
WEB_EXPECTED = {"VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED": "true"}


def require_mapping(value: Any, description: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ValueError(f"{description} is missing or is not an object")
    return value


def validate(model: dict[str, Any]) -> dict[str, Any]:
    services = require_mapping(model.get("services"), "services")
    parking = require_mapping(services.get("parking-service"), "parking-service")
    parking_env = require_mapping(parking.get("environment"), "parking-service.environment")
    web = require_mapping(services.get("web"), "web")
    web_build = require_mapping(web.get("build"), "web.build")
    web_args = require_mapping(web_build.get("args"), "web.build.args")

    errors: list[str] = []
    for key, expected in PARKING_EXPECTED.items():
        if key not in parking_env:
            errors.append(f"parking-service.environment.{key} is absent")
        elif str(parking_env[key]) != expected:
            errors.append(
                f"parking-service.environment.{key} expected {expected!r}, "
                f"got {str(parking_env[key])!r}"
            )
    for key, expected in WEB_EXPECTED.items():
        if key not in web_args:
            errors.append(f"web.build.args.{key} is absent")
        elif str(web_args[key]) != expected:
            errors.append(
                f"web.build.args.{key} expected {expected!r}, got {str(web_args[key])!r}"
            )

    # Explicit parity guard: a flag-on web bundle cannot be paired with a
    # backend that has municipal discovery absent or disabled.
    if (
        str(web_args.get("VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED", "")) == "true"
        and str(parking_env.get("PARKIO_MUNICIPAL_ENABLED", "")) != "true"
    ):
        errors.append("municipal discovery frontend/backend parity is broken")

    if errors:
        raise ValueError("; ".join(errors))

    return {
        "schemaVersion": 1,
        "source": "resolved-compose-model",
        "parkingEnvironment": {key: str(parking_env[key]) for key in PARKING_EXPECTED},
        "webBuildArguments": {key: str(web_args[key]) for key in WEB_EXPECTED},
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--evidence",
        action="store_true",
        help="emit only the allowlisted non-secret evidence JSON",
    )
    args = parser.parse_args()
    try:
        model = json.load(sys.stdin)
        if not isinstance(model, dict):
            raise ValueError("resolved Compose model is not an object")
        evidence = validate(model)
    except (json.JSONDecodeError, ValueError) as exc:
        print(f"FAIL: invite-production feature configuration: {exc}", file=sys.stderr)
        return 1

    if not args.evidence:
        print("PASS: invite-production resolved feature matrix and frontend/backend parity")
    print(json.dumps(evidence, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
