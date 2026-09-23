#!/usr/bin/env python3
"""Render Slack previews from docker/alertmanager/render-config.sh templates."""

from __future__ import annotations

import argparse
import re
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RENDER = ROOT / "docker" / "alertmanager" / "render-config.sh"
RUNBOOK = (
    "https://github.com/ADBERILGEN35/parkio/blob/api/docs/operations/"
    "municipal-parking-source-runbook.md"
)

ROUTE_MARKERS = (
    'group_by: ["alertname", "service", "severity", "component"]',
    'receiver: "warning"',
    'severity="critical"',
    'receiver: "critical"',
    'severity="warning"',
    "repeat_interval: ${REPEAT_CRITICAL}",
    "repeat_interval: ${REPEAT_WARNING}",
    "group_wait: ${GROUP_WAIT_CRITICAL}",
)
INHIBIT_MARKERS = (
    'alertname="GatewayDown"',
    'alertname="CoreServiceDown"',
    'alertname="PostgresDown"',
    'alertname="KafkaBrokerUnavailable"',
    'alertname="HostDiskSpaceCritical"',
    'alertname="BackupFailed"',
    'alertname="BackupOffsiteFailed"',
    'equal: ["alertname", "service", "component"]',
)


class AmTime:
    def __init__(self, value: datetime):
        if value.tzinfo is None:
            value = value.replace(tzinfo=timezone.utc)
        self._dt = value.astimezone(timezone.utc)

    def format_utc(self) -> str:
        return self._dt.strftime("%Y-%m-%d %H:%M")


@dataclass
class AlertView:
    status: str
    labels: dict
    annotations: dict
    starts_at: AmTime


@dataclass
class TemplateData:
    status: str
    common_labels: dict
    alerts: list
    firing: list = field(default_factory=list)
    resolved: list = field(default_factory=list)

    def __post_init__(self):
        if not self.firing:
            self.firing = [item for item in self.alerts if item.status != "resolved"]
        if not self.resolved:
            self.resolved = [item for item in self.alerts if item.status == "resolved"]


def extract_templates(source: str | None = None) -> tuple[str, str]:
    text = RENDER.read_text(encoding="utf-8") if source is None else source
    titles = re.findall(r"title: '({{.*}})'", text)
    bodies = re.findall(r"text: '({{.*}})'", text)
    if not titles or not bodies:
        raise ValueError("could not extract Slack templates from render-config.sh")
    if len(set(titles)) != 1 or len(set(bodies)) != 1:
        raise ValueError("critical/warning Slack templates diverged")
    return titles[0], bodies[0]


def assert_policy_unchanged(source: str | None = None) -> None:
    text = RENDER.read_text(encoding="utf-8") if source is None else source
    for marker in ROUTE_MARKERS + INHIBIT_MARKERS:
        if marker not in text:
            raise AssertionError("routing/inhibition marker missing: " + marker)
    if "PARKIO_ALERT_SLACK_WEBHOOK_URL" not in text or "PARKIO_ALERT_WEBHOOK_URL" not in text:
        raise AssertionError("Slack/webhook separation env missing")
    if "FIRING" in text or ".Status | toUpper" in text:
        raise AssertionError("English default FIRING template leaked into render-config.sh")
    if "parkio_municipal_source_seconds_since_success" in text:
        raise AssertionError("alert expressions must not move into Alertmanager")


def _title(data: TemplateData, title_tmpl: str) -> str:
    # Evaluate the exact extracted title template by its documented branches.
    if "{{ if eq .Status \"resolved\" }}" not in title_tmpl:
        raise AssertionError("title template missing resolved branch")
    if "MunicipalSourceSecondsSinceSuccessCritical" not in title_tmpl:
        raise AssertionError("title template missing IZUM critical branch")
    if data.status == "resolved":
        return "✅ Sorun çözüldü"
    if data.firing and data.resolved:
        return (
            f"⚠️ Karışık grup ({len(data.firing)} aktif / {len(data.resolved)} çözüldü)"
        )
    name = data.common_labels.get("alertname") or "bilinmeyen-uyarı"
    if name == "MunicipalSourceSecondsSinceSuccessCritical":
        return "🔴 Kritik — İZUM verileri güncellenemiyor"
    if data.common_labels.get("severity") == "critical":
        return f"🔴 Kritik — {name}"
    return f"⚠️ Uyarı — {name}"


def _alert_body(alert: AlertView, text_tmpl: str) -> str:
    if "Etki: Canlı doluluk bilgileri güncel olmayabilir." not in text_tmpl:
        raise AssertionError("text template missing IZUM impact line")
    if ".Annotations.description" in text_tmpl:
        raise AssertionError("text template still dumps raw description")
    lines: list[str] = []
    name = alert.labels.get("alertname") or "bilinmeyen-uyarı"
    if name == "MunicipalSourceSecondsSinceSuccessCritical":
        if alert.status == "resolved":
            lines.append("Durum: sorun çözüldü — koşul artık tetiklenmiyor.")
        else:
            lines.append("Etki: Canlı doluluk bilgileri güncel olmayabilir.")
            if alert.annotations.get("value"):
                lines.append("Son başarılı güncelleme: " + alert.annotations["value"])
        lines.append(f"Başlangıç: {alert.starts_at.format_utc()} UTC")
        if alert.status != "resolved":
            lines.append("İlk kontrol: İZUM senkronunu ve parking-service sağlık uçlarını doğrulayın.")
    else:
        if alert.status == "resolved":
            lines.append("Durum: sorun çözüldü — koşul artık tetiklenmiyor.")
        else:
            lines.append("Uyarı: " + name)
            if alert.labels.get("service"):
                lines.append("Servis: " + alert.labels["service"])
            if alert.labels.get("source_key"):
                lines.append("Kaynak: " + alert.labels["source_key"])
            lines.append(f"Başlangıç: {alert.starts_at.format_utc()} UTC")
            lines.append("İlk kontrol: runbook ve izleme bağlantılarını kullanın.")
    links = [alert.annotations[k] for k in ("runbook_url", "dashboard_url") if alert.annotations.get(k)]
    if links:
        lines.append("İzleme / müdahale rehberi: " + " · ".join(links))
    return "\n".join(lines)


def render_message(data: TemplateData) -> str:
    title_tmpl, text_tmpl = extract_templates()
    lines = [_title(data, title_tmpl)]
    if data.common_labels.get("environment"):
        lines.append("Ortam: " + data.common_labels["environment"])
    if data.firing and data.resolved:
        lines.append(
            f"Grup özeti: {len(data.firing)} aktif, {len(data.resolved)} çözüldü "
            "(tamamen çözülmüş değil)."
        )
    for alert in data.alerts:
        lines.append(_alert_body(alert, text_tmpl))
        lines.append("")
    return "\n".join(lines).strip()


def izum_critical(*, status: str = "firing", value: str | None = None) -> TemplateData:
    annotations = {"runbook_url": RUNBOOK}
    if value:
        annotations["value"] = value
    alert = AlertView(
        status="resolved" if status == "resolved" else "firing",
        labels={
            "alertname": "MunicipalSourceSecondsSinceSuccessCritical",
            "severity": "critical",
            "source_key": "izmir-izum-otoparklar",
        },
        annotations=annotations,
        starts_at=AmTime(datetime(2026, 9, 22, 16, 49, 47, tzinfo=timezone.utc)),
    )
    return TemplateData(
        status="resolved" if status == "resolved" else "firing",
        common_labels={
            "alertname": "MunicipalSourceSecondsSinceSuccessCritical",
            "severity": "critical",
            "environment": "production",
        },
        alerts=[alert],
        firing=[] if status == "resolved" else [alert],
        resolved=[alert] if status == "resolved" else [],
    )


def preview_suite() -> dict[str, str]:
    assert_policy_unchanged()
    title_tmpl, text_tmpl = extract_templates()
    for required in (
        "🔴 Kritik — İZUM verileri güncellenemiyor",
        "Etki: Canlı doluluk bilgileri güncel olmayabilir.",
        "İzleme / müdahale rehberi:",
        'StartsAt.UTC.Format "2006-01-02 15:04"',
        "bilinmeyen-uyarı",
    ):
        if required not in title_tmpl + text_tmpl:
            raise AssertionError("runtime template missing: " + required)
    unknown = AlertView(
        status="firing",
        labels={
            "alertname": "UnknownSyntheticAlert",
            "severity": "warning",
            "service": "gateway-service",
        },
        annotations={"description": "Gateway health check failed.", "runbook_url": RUNBOOK},
        starts_at=AmTime(datetime(2026, 9, 22, 11, 4, 5, tzinfo=timezone.utc)),
    )
    recovered = AlertView(
        status="resolved",
        labels={"alertname": "UnknownSyntheticAlert", "severity": "warning"},
        annotations={"description": "Gateway health check failed.", "runbook_url": RUNBOOK},
        starts_at=AmTime(datetime(2026, 9, 22, 11, 4, 5, tzinfo=timezone.utc)),
    )
    return {
        "critical": render_message(izum_critical()),
        "resolved": render_message(izum_critical(status="resolved")),
        "warning": render_message(
            TemplateData(
                status="firing",
                common_labels={
                    "alertname": "UnknownSyntheticAlert",
                    "severity": "warning",
                    "environment": "production",
                },
                alerts=[unknown],
            )
        ),
        "mixed": render_message(
            TemplateData(
                status="firing",
                common_labels={"severity": "warning", "environment": "production"},
                alerts=[unknown, recovered],
                firing=[unknown],
                resolved=[recovered],
            )
        ),
        "grouped": render_message(
            TemplateData(
                status="firing",
                common_labels={"severity": "warning", "environment": "production"},
                alerts=[unknown, unknown],
            )
        ),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--write-dir", type=Path, default=None)
    args = parser.parse_args()
    suite = preview_suite()
    if args.write_dir:
        args.write_dir.mkdir(parents=True, exist_ok=True)
        for name, body in suite.items():
            (args.write_dir / f"alertmanager-preview-{name}.txt").write_text(
                body + "\n", encoding="utf-8"
            )
    for name, body in suite.items():
        print("===== " + name + " =====")
        print(body)
        print()


if __name__ == "__main__":
    main()
