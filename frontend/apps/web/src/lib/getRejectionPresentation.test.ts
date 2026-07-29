import { describe, expect, it } from 'vitest';
import i18n from '@/i18n';
import { getRejectionPresentation } from './getRejectionPresentation';

describe('getRejectionPresentation', () => {
  const t = i18n.getFixedT('en', 'parking');

  it('prefers localized product copy over server message for known AI codes', () => {
    const result = getRejectionPresentation({
      status: 'REJECTED',
      code: 'CLEARLY_UNRELATED_CONTENT',
      source: 'AI_POLICY',
      serverMessage: 'Raw server text must not win.',
      t,
    });

    expect(result.variant).toBe('AI_POLICY');
    expect(result.title).toMatch(/AI decision/i);
    expect(result.sourceLabel).toMatch(/AI validation/i);
    expect(result.message).toMatch(/parking space or road context/i);
    expect(result.message).not.toContain('Raw server text');
    expect(result.displayStatus).toBeNull();
  });

  it('marks LEGACY_POLICY_RESET as system migration with explicit non-AI copy', () => {
    const result = getRejectionPresentation({
      status: 'REJECTED',
      code: 'LEGACY_POLICY_RESET',
      source: 'SYSTEM_MIGRATION',
      serverMessage: 'Old snapshot',
      t,
    });

    expect(result.variant).toBe('SYSTEM_MIGRATION');
    expect(result.title).toMatch(/system policy migration/i);
    expect(result.sourceLabel).toMatch(/system migration/i);
    expect(result.displayStatus).toMatch(/system policy migration/i);
    expect(result.message).toMatch(/does not mean the photo was judged inappropriate by AI/i);
    expect(result.tone).toBe('neutral');
  });

  it('shows moderator note without replacing product moderator title', () => {
    const result = getRejectionPresentation({
      status: 'REJECTED',
      code: 'MANUAL_MODERATOR_REJECTION',
      source: 'MODERATOR',
      serverMessage: 'Please re-upload a street photo.',
      moderatorNote: 'Please re-upload a street photo.',
      t,
    });

    expect(result.variant).toBe('MODERATOR');
    expect(result.title).toMatch(/moderator decision/i);
    expect(result.moderatorNote).toBe('Please re-upload a street photo.');
    expect(result.message).toMatch(/rejected by a moderator/i);
  });

  it('falls back to server message then generic copy for unknown codes', () => {
    const withServer = getRejectionPresentation({
      code: 'BRAND_NEW_CODE',
      source: 'AI_POLICY',
      serverMessage: 'Compat fallback',
      t,
    });
    expect(withServer.message).toBe('Compat fallback');

    const withoutServer = getRejectionPresentation({
      code: 'BRAND_NEW_CODE',
      source: 'AI_POLICY',
      t,
    });
    expect(withoutServer.message).toMatch(/rejected under the validation policy/i);
  });

  it('resolves TR copy for legacy migration', async () => {
    await i18n.changeLanguage('tr');
    const tr = i18n.getFixedT('tr', 'parking');
    const result = getRejectionPresentation({
      code: 'LEGACY_POLICY_RESET',
      source: 'SYSTEM_MIGRATION',
      status: 'REJECTED',
      t: tr,
    });
    expect(result.title).toBe('Sistem politika geçişi');
    expect(result.sourceLabel).toBe('Sistem migrasyonu');
    expect(result.displayStatus).toBe('Reddedildi — Sistem politika geçişi');
    expect(result.message).toMatch(/AI tarafından uygunsuz bulunduğu anlamına gelmez/);
    await i18n.changeLanguage('en');
  });
});
