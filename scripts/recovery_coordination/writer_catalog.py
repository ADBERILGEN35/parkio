"""Allowlisted writer identities derived from repository deployment files.

Do not invent production host unit names. Isolated tests use the same
compose service names under an isolated project prefix. Production systemd
units are recorded for documentation only; the isolated adapter will not
control them.
"""
from __future__ import annotations

from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]

# Logical writer -> identities that already exist in this repository.
CATALOG = {
    "slack_worker": {
        "systemd_unit": "parkio-slack-biz-worker.service",
        "systemd_source": "scripts/slack_biz/deploy/civo/parkio-slack-biz-worker.service",
        "compose_service": "slack-biz-worker",
        "compose_source": "docker/docker-compose.slack-biz.yml",
        "isolated_compose_service": "slack-biz-worker",
        "mechanism": "compose",
    },
    "inbox_consumer": {
        "systemd_unit": "parkio-slack-biz-waitlist-consumer.service",
        "systemd_source": "scripts/slack_biz/deploy/civo/parkio-slack-biz-waitlist-consumer.service",
        "compose_service": None,
        "compose_source": None,
        "isolated_compose_service": "slack-biz-waitlist-consumer",
        "mechanism": "compose",
        "note": "No compose service exists in docker-compose.slack-biz.yml; systemd only in the Civo unit.",
    },
    "fluent_bit": {
        "systemd_unit": "parkio-nr-log-continuous.service",
        "systemd_source": "scripts/newrelic_log_pilot/systemd/parkio-nr-log-continuous.service",
        "compose_service": "fluent-bit-nr-pilot",
        "compose_source": "docker/docker-compose.newrelic-log-pilot.yml",
        "compose_project_documented": "parkio-nr-log-continuous",
        "isolated_compose_service": "fluent-bit-nr-pilot",
        "mechanism": "compose",
        "note": "Production oneshot stops helper+gate+collector together. Isolated compose still uses the service name.",
    },
    "nr_gate": {
        "systemd_unit": None,
        "compose_service": "nr-budget-gate",
        "compose_source": "docker/docker-compose.newrelic-log-pilot.yml",
        "compose_project_documented": "parkio-nr-log-continuous",
        "isolated_compose_service": "nr-budget-gate",
        "mechanism": "compose",
    },
    "nr_source": {
        "systemd_unit": "parkio-nr-log-source.service",
        "systemd_source": "scripts/newrelic_log_pilot/systemd/parkio-nr-log-source.service",
        "compose_service": None,
        "compose_source": None,
        "isolated_compose_service": "nr-log-source",
        "mechanism": "compose",
        "note": "Helper is a systemd unit; no dedicated compose service in the NR overlay.",
    },
    "gateway_exporter": {
        "systemd_unit": None,
        "compose_service": None,
        "isolated_compose_service": None,
        "mechanism": "export_pause_file",
        "deploy_requirement": "PARKIO_WAITLIST_OPS_NOTIFICATIONS_EXPORT_PAUSE_FILE on a later gateway deploy",
        "note": "Not a process. Correlated pause request/ack on the inbox bind. Do not flip ops-notifications.enabled.",
    },
}

NR_GUARD_TIMER = {
    "systemd_unit": "parkio-nr-log-continuous-guard.timer",
    "systemd_source": "scripts/newrelic_log_pilot/systemd/parkio-nr-log-continuous-guard.timer",
}

FORBIDDEN_COMPOSE_PROJECTS = frozenset({
    "parkio-nr-log-continuous",
    "parkio",
    "parkio-invite",
})
ISOLATED_PROJECT_PREFIX = "parkio-writer-control-isolated-"
ISOLATED_COMPOSE_FILE = "scripts/recovery_coordination/isolated/docker-compose.writer-control-isolated.yml"


def catalog_entry(name: str) -> dict:
    if name not in CATALOG:
        raise KeyError(f"writer {name} is not in the repository allowlist")
    return CATALOG[name]


def verify_catalog_against_repo(root: Path | None = None) -> list[str]:
    """Return missing-source errors. Empty means every cited file contains the identity."""
    root = root or REPO_ROOT
    errors = []
    for name, spec in CATALOG.items():
        unit_file = spec.get("systemd_source")
        unit = spec.get("systemd_unit")
        if unit_file:
            path = root / unit_file
            if not path.is_file():
                errors.append(f"{name}: missing {unit_file}")
            elif unit and f"[Unit]" not in path.read_text(encoding="utf-8"):
                errors.append(f"{name}: {unit_file} is not a unit file")
        compose_file = spec.get("compose_source")
        service = spec.get("compose_service")
        if compose_file and service:
            path = root / compose_file
            if not path.is_file():
                errors.append(f"{name}: missing {compose_file}")
            elif f"{service}:" not in path.read_text(encoding="utf-8"):
                errors.append(f"{name}: {service} not in {compose_file}")
    timer = root / NR_GUARD_TIMER["systemd_source"]
    if not timer.is_file():
        errors.append(f"missing {NR_GUARD_TIMER['systemd_source']}")
    isolated = root / ISOLATED_COMPOSE_FILE
    if not isolated.is_file():
        errors.append(f"missing isolated compose {ISOLATED_COMPOSE_FILE}")
    else:
        body = isolated.read_text(encoding="utf-8")
        for name, spec in CATALOG.items():
            service = spec.get("isolated_compose_service")
            if service and f"{service}:" not in body:
                errors.append(f"{name}: isolated compose missing {service}")
    return errors
