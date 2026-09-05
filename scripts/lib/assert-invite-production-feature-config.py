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
    "PARKIO_PUBLIC_EXPLORE_ENABLED": "false",
    "PARKIO_PUBLIC_EXPLORE_ALLOWED_SOURCE_FAMILIES": "",
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
GATEWAY_EXPECTED = {"PARKIO_PUBLIC_EXPLORE_ENABLED": "false"}
WEB_EXPECTED = {
    "VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED": "true",
    "VITE_PUBLIC_EXPLORE_ENABLED": "false",
    "VITE_REGISTRATION_MODE": "closed",
}


def require_mapping(value: Any, description: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ValueError(f"{description} is missing or is not an object")
    return value


def validate(model: dict[str, Any], mode: str = "off", authorization: str = "") -> dict[str, Any]:
    if mode not in ("off", "izum-readonly"):
        raise ValueError("unsupported public explore mode")
    if mode == "izum-readonly" and authorization != "GOOGLE-STARTUP-REAPPLY-01E-B":
        raise ValueError("izum-readonly requires dedicated public explore authorization")
    if mode == "off" and authorization:
        raise ValueError("OFF mode must not carry public explore authorization")
    enabled = "true" if mode == "izum-readonly" else "false"
    parking_expected = {**PARKING_EXPECTED,
                        "PARKIO_PUBLIC_EXPLORE_ENABLED": enabled,
                        "PARKIO_PUBLIC_EXPLORE_ALLOWED_SOURCE_FAMILIES": "izum" if mode == "izum-readonly" else ""}
    gateway_expected = {"PARKIO_PUBLIC_EXPLORE_ENABLED": enabled}
    web_expected = {**WEB_EXPECTED, "VITE_PUBLIC_EXPLORE_ENABLED": enabled}
    services = require_mapping(model.get("services"), "services")
    parking = require_mapping(services.get("parking-service"), "parking-service")
    parking_env = require_mapping(parking.get("environment"), "parking-service.environment")
    gateway = require_mapping(services.get("gateway-service"), "gateway-service")
    gateway_env = require_mapping(gateway.get("environment"), "gateway-service.environment")
    web = require_mapping(services.get("web"), "web")
    web_build = require_mapping(web.get("build"), "web.build")
    web_args = require_mapping(web_build.get("args"), "web.build.args")
    auth = require_mapping(services.get("auth-service"), "auth-service")
    auth_env = require_mapping(auth.get("environment"), "auth-service.environment")

    errors: list[str] = []
    if str(auth_env.get("PARKIO_REGISTRATION_MODE", "")) != "closed":
        errors.append("auth-service registration must remain closed")
    for key, expected in parking_expected.items():
        if key not in parking_env:
            errors.append(f"parking-service.environment.{key} is absent")
        elif str(parking_env[key]) != expected:
            errors.append(
                f"parking-service.environment.{key} expected {expected!r}, "
                f"got {str(parking_env[key])!r}"
            )
    for key, expected in web_expected.items():
        if key not in web_args:
            errors.append(f"web.build.args.{key} is absent")
        elif str(web_args[key]) != expected:
            errors.append(
                f"web.build.args.{key} expected {expected!r}, got {str(web_args[key])!r}"
            )
    for key, expected in gateway_expected.items():
        if key not in gateway_env:
            errors.append(f"gateway-service.environment.{key} is absent")
        elif str(gateway_env[key]) != expected:
            errors.append(
                f"gateway-service.environment.{key} expected {expected!r}, "
                f"got {str(gateway_env[key])!r}"
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
        "publicExploreMode": mode,
        "registrationMode": "closed",
        "parkingEnvironment": {key: str(parking_env[key]) for key in PARKING_EXPECTED},
        "gatewayEnvironment": {key: str(gateway_env[key]) for key in GATEWAY_EXPECTED},
        "webBuildArguments": {key: str(web_args[key]) for key in WEB_EXPECTED},
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--public-explore-mode", default="off")
    parser.add_argument("--public-explore-authorization", default="")
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
        evidence = validate(model, args.public_explore_mode, args.public_explore_authorization)
    except (json.JSONDecodeError, ValueError) as exc:
        print(f"FAIL: invite-production feature configuration: {exc}", file=sys.stderr)
        return 1

    if not args.evidence:
        print("PASS: invite-production resolved feature matrix and frontend/backend parity")
    print(json.dumps(evidence, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
