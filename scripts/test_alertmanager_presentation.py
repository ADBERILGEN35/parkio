#!/usr/bin/env python3
from pathlib import Path
import sys

_ROOT = Path(__file__).resolve().parent
if str(_ROOT) not in sys.path:
    sys.path.insert(0, str(_ROOT))
if str(_ROOT.parent) not in sys.path:
    sys.path.insert(0, str(_ROOT.parent))

import alertmanager_slack_preview as preview

def test_policy_and_izum_preview():
    from slack_biz.safety import key_looks_sensitive, strip_sensitive_dict

    assert key_looks_sensitive("full_name") is True
    assert key_looks_sensitive("waitlist_display_name") is False
    assert "waitlist_display_name" in strip_sensitive_dict({"waitlist_display_name": "Sentetik Abone"})
    preview.assert_policy_unchanged()
    title, text = preview.extract_templates()
    assert '| default' not in title + text
    assert preview.RENDER.read_text(encoding='utf-8').count('| default') == 0
    assert 'MunicipalSourceSecondsSinceSuccessCritical' in title
    assert 'Etki: Canlı doluluk bilgileri güncel olmayabilir.' in text
    assert '.Annotations.description' not in text
    assert 'FIRING' not in title + text
    suite = preview.preview_suite()
    critical = suite['critical']
    assert critical.startswith('🔴 Kritik — İZUM verileri güncellenemiyor')
    assert 'Etki: Canlı doluluk bilgileri güncel olmayabilir.' in critical
    assert 'Son başarılı güncelleme:' not in critical
    assert 'Başlangıç: 2026-09-22 16:49 UTC' in critical
    assert 'İlk kontrol: İZUM senkronunu ve parking-service sağlık uçlarını doğrulayın.' in critical
    assert 'municipal-parking-source-runbook.md' in critical
    assert 'Gateway health check failed.' not in critical
    assert '[FIRING]' not in critical
    warning = suite['warning']
    assert 'Uyarı: UnknownSyntheticAlert' in warning
    assert 'Gateway health check failed.' not in warning
    assert 'bilinmeyen-uyarı' not in warning or 'UnknownSyntheticAlert' in warning
    mixed = suite['mixed']
    assert 'Karışık grup' in mixed
    assert 'aktif' in mixed
    resolved = suite['resolved']
    assert resolved.startswith('✅ Sorun çözüldü')
    assert 'koşul artık tetiklenmiyor' in resolved
    grouped = suite['grouped']
    assert grouped.count('Uyarı: UnknownSyntheticAlert') == 2

if __name__ == '__main__':
    test_policy_and_izum_preview()
    out = Path(__file__).resolve().parents[1] / 'agent-tools' / 'parkio-waitlist-name-slack-readable-01'
    preview.main.__wrapped__ if False else None
    suite = preview.preview_suite()
    for name, body in suite.items():
        (out / f'alertmanager-preview-{name}.txt').write_text(body + '\n', encoding='utf-8')
    print('PASS alertmanager presentation')
