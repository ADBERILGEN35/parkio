import { getRejectionPresentation } from '../getRejectionPresentation';
import type { Translator } from '@/i18n/LocaleProvider';
import { en } from '@/i18n/translations';

const t: Translator = (key) => en[key] ?? key;

describe('getRejectionPresentation', () => {
  it('maps AI policy to i18n reason and danger tone', () => {
    const result = getRejectionPresentation({
      status: 'REJECTED',
      code: 'CLEARLY_UNRELATED_CONTENT',
      source: 'AI_POLICY',
      serverMessage: 'Server snapshot',
      t,
    });
    expect(result.variant).toBe('AI_POLICY');
    expect(result.tone).toBe('danger');
    expect(result.message).toMatch(/does not appear related to a parking space/i);
    expect(result.moderatorNote).toBeNull();
  });

  it('maps moderator with separate note', () => {
    const result = getRejectionPresentation({
      status: 'REJECTED',
      code: 'MANUAL_MODERATOR_REJECTION',
      source: 'MODERATOR',
      serverMessage: 'Catalog default',
      moderatorNote: 'User uploaded a screenshot.',
      t,
    });
    expect(result.variant).toBe('MODERATOR');
    expect(result.tone).toBe('warning');
    expect(result.moderatorNote).toBe('User uploaded a screenshot.');
  });

  it('maps system migration with display status and neutral tone', () => {
    const result = getRejectionPresentation({
      status: 'REJECTED',
      code: 'LEGACY_POLICY_RESET',
      source: 'SYSTEM_MIGRATION',
      serverMessage: 'Old snapshot',
      t,
    });
    expect(result.variant).toBe('SYSTEM_MIGRATION');
    expect(result.tone).toBe('neutral');
    expect(result.displayStatus).toMatch(/System policy migration/i);
    expect(result.message).toMatch(/migration from the old validation policy/i);
  });

  it('falls back to server message for unknown codes', () => {
    const result = getRejectionPresentation({
      code: 'FUTURE_UNKNOWN_CODE',
      source: 'AI_POLICY',
      serverMessage: 'Compat fallback',
      t,
    });
    expect(result.message).toBe('Compat fallback');
  });
});
