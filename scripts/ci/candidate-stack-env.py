#!/usr/bin/env python3
"""Write the CI-only env file for a candidate full-stack run (candidate-images.yml).

Starts from docker/.env.hosted-beta.example and overrides the values a disposable CI stack needs:
synthetic domains, a fresh RSA key for the JWT signer, synthetic secrets, and the candidate image
tag so docker-compose.images.yml resolves parkio/<service>:<tag>. Mirrors the runtime-validation
workflow's env preparation; nothing here is a production value.

usage: candidate-stack-env.py --image-tag TAG --git-sha SHA --created ISO --jwt-pem FILE --out ENVFILE
"""
from __future__ import annotations

import secrets
import argparse
from pathlib import Path


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--example", default="docker/.env.hosted-beta.example")
    ap.add_argument("--image-tag", required=True)
    ap.add_argument("--git-sha", required=True)
    ap.add_argument("--created", required=True)
    ap.add_argument("--jwt-pem", required=True, help="PEM private key file generated for this run")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    pem = Path(args.jwt_pem).read_text().strip().replace("\n", "\\n")
    overrides = {
        "PARKIO_DOMAIN": "api.candidate.localhost",
        "PARKIO_WEB_DOMAIN": "app.candidate.localhost",
        "PARKIO_WEB_UPSTREAM": "web:80",
        "PARKIO_MEDIA_DOMAIN": "media.candidate.localhost",
        "PARKIO_ACME_EMAIL": "ops@example.invalid",
        "VITE_API_BASE_URL": "https://api.candidate.localhost/api/v1",
        "VITE_MAPTILER_KEY": "ci-public-map-key",
        "PARKIO_IMAGE_TAG": args.image_tag,
        "PARKIO_GIT_SHA": args.git_sha,
        "PARKIO_IMAGE_CREATED": args.created,
        "PARKIO_CORS_ALLOWED_ORIGINS": "https://app.candidate.localhost",
        "PARKIO_JWT_PRIVATE_KEY_PEM": f'"{pem}"',
        "PARKIO_GATEWAY_INTERNAL_SECRET": "candidate-acceptance-gateway-secret",
        # CL-F15 v3: a throwaway login-throttle keyed-hashing secret, never the example placeholder.
        "PARKIO_LOGIN_THROTTLE_HMAC_KEY": secrets.token_urlsafe(48),
        "PARKIO_EMAIL_PROVIDER": "logging",
        "PARKIO_RESEND_API_KEY": "",
        "KAFKA_CLUSTER_ID": "MkU3OEVBNTcwNTJENDM2Qk",
        "MINIO_ROOT_PASSWORD": "candidate-acceptance-minio-password",
        "PARKIO_MEDIA_STORAGE_PUBLIC_ENDPOINT": "https://media.candidate.localhost",
        "PARKIO_ALERT_SLACK_WEBHOOK_URL": "",
        "PARKIO_ALERT_HEARTBEAT_URL": "",
        "GRAFANA_ADMIN_PASSWORD": "candidate-acceptance-grafana-password",
        "REDIS_PASSWORD": "candidate-acceptance-redis-password",
    }

    example = Path(args.example).read_text().splitlines()
    present = {line.split("=", 1)[0] for line in example if line and not line.lstrip().startswith("#") and "=" in line}
    lines = []
    seen = set()
    for line in example:
        if not line or line.lstrip().startswith("#") or "=" not in line:
            lines.append(line)
            continue
        key, _ = line.split("=", 1)
        if key in overrides:
            lines.append(f"{key}={overrides[key]}")
            seen.add(key)
        else:
            lines.append(line)
    for key, value in overrides.items():
        if key not in seen and key not in present:
            lines.append(f"{key}={value}")
    Path(args.out).write_text("\n".join(lines) + "\n")
    print(f"wrote {args.out} ({len(overrides)} overrides, image tag {args.image_tag})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
