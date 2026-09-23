#!/usr/bin/env python3
from pathlib import Path
import sys

_ROOT = Path(__file__).resolve().parent
if str(_ROOT) not in sys.path:
    sys.path.insert(0, str(_ROOT))
if str(_ROOT.parent) not in sys.path:
    sys.path.insert(0, str(_ROOT.parent))

import alertmanager_slack_preview as preview


def test_policy_and_presentation():
    from slack_biz.safety import key_looks_sensitive, strip_sensitive_dict

    assert key_looks_sensitive("full_name") is True
    assert key_looks_sensitive("waitlist_display_name") is False
    assert "waitlist_display_name" in strip_sensitive_dict({"waitlist_display_name": "Sentetik Abone"})
    preview.assert_policy_unchanged()
    title, text = preview.extract_templates()
    assert "| default" not in title + text
    assert preview.RENDER.read_text(encoding="utf-8").count("| default") == 0
    assert 'match "ConsecutiveFailures"' in title
    assert 'match "SecondsSinceSuccess"' in title
    assert 'osm-geofabrik-turkey' in title
    assert "Gateway kapalı" in title
    assert "bilinmeyen uyarı" in title
    assert ".StartsAt" in text
    assert "Başlangıç (UTC):" in text
    assert "reReplaceAll" in text
    raw = preview.RENDER.read_bytes()
    assert raw.count(b'{{ "\\\\n" }}') >= 40
    assert b'{{ "\\n" }}' not in raw
    assert ".Annotations.description" not in text
    assert "FIRING" not in title + text
    suite = preview.preview_suite()

    consec = suite["consecutive-critical"]
    assert consec.startswith("🔴 Kritik — İZUM ardışık hatalar")
    assert "Durum: ardışık hatalar sürüyor." in consec
    assert "Durum: son başarılı güncellemeden beri veri alınamadı." not in consec
    assert "Kaynak: İZUM (izmir-izum-otoparklar)" in consec
    assert "Etki: Canlı doluluk bilgileri güncel olmayabilir." in consec
    assert "Başlangıç (UTC): 2026-09-23 16:49" in consec
    assert preview.RUNBOOK in consec
    assert "Tanı: MunicipalSourceConsecutiveFailuresCritical" in consec
    assert consec.index("Kaynak:") < consec.index("Tanı:")

    stale_w = suite["stale-warning"]
    assert stale_w.startswith("⚠️ Uyarı — İZUM verileri güncellenemiyor")
    assert "başarılı güncelleme penceresi aşıldı." in stale_w
    assert "veri alınamadı" not in stale_w
    assert "ardışık hatalar sürüyor." not in stale_w

    stale_c = suite["stale-critical"]
    assert stale_c.startswith("🔴 Kritik — İZUM verileri güncellenemiyor")

    ispark = suite["ispark-consecutive"]
    assert "İSPARK ardışık hatalar" in ispark
    assert "İZUM" not in ispark
    assert "istanbul-ispark-parks" in ispark

    osm = suite["osm-consecutive"]
    assert "OSM ardışık hatalar" in osm
    assert "İZUM" not in osm
    assert "canlı doluluk kaynağı değildir" in osm

    relative = suite["relative-runbook"]
    assert preview.RUNBOOK in relative
    assert "docs/operations/municipal-parking-source-runbook.md" in relative
    assert relative.count("https://github.com/ADBERILGEN35/parkio/blob/api/docs/operations/municipal-parking-source-runbook.md") == 1

    resolved = suite["resolved"]
    assert resolved.startswith("✅ Sorun çözüldü — İZUM")
    assert "Kaynak: İZUM (izmir-izum-otoparklar)" in resolved
    assert "koşul artık tetiklenmiyor" in resolved
    assert "Başlangıç (UTC):" in resolved

    unknown = suite["unknown"]
    assert unknown.startswith("⚠️ Uyarı — bilinmeyen uyarı")
    assert "UnknownSyntheticAlert" in unknown
    assert unknown.index("⚠️ Uyarı — bilinmeyen uyarı") < unknown.index("Tanı: UnknownSyntheticAlert")
    assert "Gateway health check failed." not in unknown
    assert "Servis: gateway-service" in unknown
    assert "Etki: gateway-service servisi etkilenebilir." in unknown
    assert "Tanı: UnknownSyntheticAlert" in unknown

    gateway = suite["gateway-down"]
    assert gateway.startswith("🔴 Kritik — Gateway kapalı")
    assert "Gateway is down" not in gateway.split("\n", 1)[0]
    assert "alert-response-runbook.md#gatewaydown" in gateway
    assert gateway.count("https://github.com/") >= 1

    mixed = suite["mixed"]
    assert "Karışık grup" in mixed
    assert "aktif" in mixed
    assert "çözüldü" in mixed

    grouped = suite["grouped"]
    assert grouped.startswith("⚠️ Uyarı — bilinmeyen uyarı")
    assert grouped.count("Tanı: UnknownSyntheticAlert") == 2

    missing = suite["missing-annotations"]
    assert "Kaynak: İZUM" in missing
    assert "parking-service sağlık uçlarını doğrulayın" in missing

    stale_run = suite["stale-running"]
    assert stale_run.startswith("⚠️ Uyarı — İZUM senkron işlemi bitmedi")
    assert "hâlâ RUNNING görünüyor" in stale_run
    assert "ardışık hatalar sürüyor." not in stale_run

    recovered = suite["recovered"]
    assert recovered.startswith("✅ İZUM toparlandı")
    assert "Durum: kaynak toparlandı." in recovered
    assert "Canlı doluluk bilgileri güncel olmayabilir." not in recovered

    occupancy = suite["occupancy-retention"]
    assert occupancy.startswith("⚠️ Uyarı — Belediye doluluk saklama yakın zamanda başarılı olmadı")
    assert "Tanı: MunicipalOccupancyRetentionStale" in occupancy
    assert "İZUM" not in occupancy


if __name__ == "__main__":
    test_policy_and_presentation()
    out = Path(__file__).resolve().parents[1] / "agent-tools" / "parkio-am-slack-presentation-01"
    out.mkdir(parents=True, exist_ok=True)
    suite = preview.preview_suite()
    for name, body in suite.items():
        (out / f"alertmanager-preview-{name}.txt").write_text(
            preview.SYNTHETIC_PREVIEW + "\n" + body + "\n", encoding="utf-8"
        )
    print("PASS alertmanager presentation")
