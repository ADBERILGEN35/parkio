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
REPO = "https://github.com/ADBERILGEN35/parkio/blob/api/"

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
    common_annotations: dict = field(default_factory=dict)

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
    if "| default" in text:
        raise AssertionError("unsupported | default leaked back into templates")


def source_name(key: str | None) -> str:
    return {
        "izmir-izum-otoparklar": "İZUM",
        "istanbul-ispark-parks": "İSPARK",
        "osm-geofabrik-turkey": "OSM",
    }.get(key or "", key or "belediye kaynağı")


def abs_url(url: str) -> str:
    if re.match(r"^https?://", url):
        return url
    return REPO + url.lstrip("/")


def _sev(labels: dict) -> str:
    return "🔴 Kritik" if labels.get("severity") == "critical" else "⚠️ Uyarı"


def _title(data: TemplateData, title_tmpl: str) -> str:
    if '{{ if eq .Status "resolved" }}' not in title_tmpl:
        raise AssertionError("title template missing resolved branch")
    if "match \"ConsecutiveFailures\"" not in title_tmpl:
        raise AssertionError("title template missing consecutive-failure branch")
    if "match \"SecondsSinceSuccess\"" not in title_tmpl:
        raise AssertionError("title template missing stale-age branch")
    if data.status == "resolved":
        return "✅ Sorun çözüldü"
    if data.firing and data.resolved:
        return (
            f"⚠️ Karışık grup ({len(data.firing)} aktif / {len(data.resolved)} çözüldü)"
        )
    name = data.common_labels.get("alertname") or ""
    src = source_name(data.common_labels.get("source_key"))
    if "ConsecutiveFailures" in name:
        return f"{_sev(data.common_labels)} — {src} ardışık hatalar"
    if "SecondsSinceSuccess" in name:
        return f"{_sev(data.common_labels)} — {src} verileri güncellenemiyor"
    if "StaleRunning" in name:
        return f"{_sev(data.common_labels)} — {src} senkron işlemi bitmedi"
    if re.search(r"Municipal(Source|Ispark|Osm)Recovered", name):
        return f"✅ {src} toparlandı"
    headline = data.common_annotations.get("summary") or name or "bilinmeyen-uyarı"
    return f"{_sev(data.common_labels)} — {headline}"


def _impact(alert: AlertView) -> str:
    key = alert.labels.get("source_key")
    if key in ("izmir-izum-otoparklar", "istanbul-ispark-parks"):
        return "Etki: Canlı doluluk bilgileri güncel olmayabilir."
    if key == "osm-geofabrik-turkey":
        return "Etki: Harita veya içe aktarma tarafı etkilenebilir; bu kaynak canlı doluluk kaynağı değildir."
    if key:
        return "Etki: Kaynak etkilenebilir; kullanıcı etkisi bu kaynağa göre değişir."
    if alert.labels.get("service"):
        return f"Etki: {alert.labels['service']} servisi etkilenebilir."
    return "Etki: Kullanıcı etkisi bu uyarının etiketlerine göre değişir."


def _alert_body(alert: AlertView, text_tmpl: str) -> str:
    if "Başlangıç (UTC):" not in text_tmpl:
        raise AssertionError("text template missing explicit UTC timestamp")
    if ".Annotations.description" in text_tmpl:
        raise AssertionError("text template still dumps raw description")
    if "reReplaceAll" not in text_tmpl:
        raise AssertionError("text template missing relative runbook conversion")
    lines: list[str] = []
    name = alert.labels.get("alertname") or ""
    if alert.status == "resolved":
        lines.append("Durum: sorun çözüldü — koşul artık tetiklenmiyor.")
    else:
        if alert.labels.get("source_key"):
            key = alert.labels["source_key"]
            lines.append(f"Kaynak: {source_name(key)} ({key})")
        elif alert.labels.get("service"):
            lines.append("Servis: " + alert.labels["service"])
        recovered = bool(re.search(r"Municipal(Source|Ispark|Osm)Recovered", name))
        if "ConsecutiveFailures" in name:
            lines.append("Durum: ardışık hatalar sürüyor.")
        elif "SecondsSinceSuccess" in name:
            lines.append("Durum: son başarılı güncellemeden beri veri alınamadı.")
        elif "StaleRunning" in name:
            lines.append("Durum: bir senkron işlemi hâlâ RUNNING görünüyor.")
        elif recovered:
            lines.append("Durum: kaynak toparlandı.")
        elif alert.annotations.get("summary"):
            lines.append("Özet: " + alert.annotations["summary"])
        if not recovered:
            lines.append(_impact(alert))
        if alert.annotations.get("operator_action"):
            lines.append("İlk kontrol: " + alert.annotations["operator_action"])
        elif alert.labels.get("source_key"):
            lines.append("İlk kontrol: Runbook’u açın; parking-service sağlık uçlarını doğrulayın.")
        else:
            lines.append("İlk kontrol: runbook ve izleme bağlantılarını kullanın.")
    lines.append(f"Başlangıç (UTC): {alert.starts_at.format_utc()}")
    links = []
    if alert.annotations.get("runbook_url"):
        links.append(abs_url(alert.annotations["runbook_url"]))
    if alert.annotations.get("dashboard_url"):
        links.append(abs_url(alert.annotations["dashboard_url"]))
    if links:
        lines.append("İzleme / müdahale rehberi: " + " · ".join(links))
    if name:
        lines.append("Tanı: " + name)
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


def municipal(
    alertname: str,
    *,
    source_key: str = "izmir-izum-otoparklar",
    severity: str = "warning",
    status: str = "firing",
    runbook: str | None = RUNBOOK,
    operator_action: str | None = "Runbook’u açın; actuator health ve kaynak SLA’sını doğrulayın.",
    starts: datetime | None = None,
) -> TemplateData:
    annotations = {}
    if runbook:
        annotations["runbook_url"] = runbook
    if operator_action:
        annotations["operator_action"] = operator_action
    alert = AlertView(
        status="resolved" if status == "resolved" else "firing",
        labels={
            "alertname": alertname,
            "severity": severity,
            "source_key": source_key,
        },
        annotations=annotations,
        starts_at=AmTime(starts or datetime(2026, 9, 23, 16, 49, 47, tzinfo=timezone.utc)),
    )
    return TemplateData(
        status="resolved" if status == "resolved" else "firing",
        common_labels={
            "alertname": alertname,
            "severity": severity,
            "source_key": source_key,
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
        'match "ConsecutiveFailures"',
        'match "SecondsSinceSuccess"',
        "Başlangıç (UTC):",
        "blob/api/",
        "bilinmeyen-uyarı",
        "Karışık grup",
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
        labels={"alertname": "UnknownSyntheticAlert", "severity": "warning", "service": "gateway-service"},
        annotations={"description": "Gateway health check failed.", "runbook_url": RUNBOOK},
        starts_at=AmTime(datetime(2026, 9, 22, 11, 4, 5, tzinfo=timezone.utc)),
    )
    gateway = AlertView(
        status="firing",
        labels={"alertname": "GatewayDown", "severity": "critical", "service": "gateway-service"},
        annotations={
            "summary": "Gateway is down",
            "runbook_url": "docs/operations/alert-response-runbook.md#gatewaydown",
        },
        starts_at=AmTime(datetime(2026, 9, 23, 12, 0, tzinfo=timezone.utc)),
    )
    return {
        "consecutive-critical": render_message(
            municipal("MunicipalSourceConsecutiveFailuresCritical", severity="critical")
        ),
        "consecutive-warning": render_message(
            municipal("MunicipalSourceConsecutiveFailuresWarning", severity="warning")
        ),
        "stale-warning": render_message(
            municipal("MunicipalSourceSecondsSinceSuccessWarning", severity="warning")
        ),
        "stale-critical": render_message(
            municipal("MunicipalSourceSecondsSinceSuccessCritical", severity="critical")
        ),
        "ispark-consecutive": render_message(
            municipal(
                "MunicipalIsparkConsecutiveFailuresWarning",
                source_key="istanbul-ispark-parks",
                severity="warning",
            )
        ),
        "osm-consecutive": render_message(
            municipal(
                "MunicipalOsmConsecutiveFailuresCritical",
                source_key="osm-geofabrik-turkey",
                severity="critical",
            )
        ),
        "relative-runbook": render_message(
            municipal(
                "MunicipalSourceSecondsSinceSuccessCritical",
                severity="critical",
                runbook="docs/operations/municipal-parking-source-runbook.md",
            )
        ),
        "resolved": render_message(
            municipal("MunicipalSourceSecondsSinceSuccessCritical", severity="critical", status="resolved")
        ),
        "unknown": render_message(
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
        "gateway-down": render_message(
            TemplateData(
                status="firing",
                common_labels={
                    "alertname": "GatewayDown",
                    "severity": "critical",
                    "service": "gateway-service",
                },
                common_annotations={"summary": "Gateway is down"},
                alerts=[gateway],
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
                common_labels={
                    "alertname": "UnknownSyntheticAlert",
                    "severity": "warning",
                    "environment": "production",
                },
                alerts=[unknown, unknown],
            )
        ),
        "missing-annotations": render_message(
            municipal(
                "MunicipalSourceConsecutiveFailuresWarning",
                severity="warning",
                runbook=None,
                operator_action=None,
            )
        ),
        "stale-running": render_message(
            municipal("MunicipalSourceStaleRunningOperation", severity="warning")
        ),
        "recovered": render_message(
            municipal("MunicipalSourceRecovered", severity="warning")
        ),
        "occupancy-retention": render_message(
            TemplateData(
                status="firing",
                common_labels={
                    "alertname": "MunicipalOccupancyRetentionStale",
                    "severity": "warning",
                    "environment": "production",
                },
                common_annotations={
                    "summary": "Belediye doluluk saklama yakın zamanda başarılı olmadı"
                },
                alerts=[
                    AlertView(
                        status="firing",
                        labels={
                            "alertname": "MunicipalOccupancyRetentionStale",
                            "severity": "warning",
                        },
                        annotations={
                            "summary": "Belediye doluluk saklama yakın zamanda başarılı olmadı",
                            "operator_action": "Runbook’u açın; actuator health ve kaynak SLA’sını doğrulayın.",
                            "runbook_url": RUNBOOK,
                        },
                        starts_at=AmTime(
                            datetime(2026, 9, 23, 16, 49, 47, tzinfo=timezone.utc)
                        ),
                    )
                ],
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
