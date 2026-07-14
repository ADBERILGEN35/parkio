import { beforeAll, describe, expect, it } from 'vitest';
import i18n from '@/i18n';
import { enumLabel, humanizeEnum } from './format';

describe('enumLabel', () => {
  beforeAll(async () => {
    await i18n.changeLanguage('en');
  });

  it('returns a known enum translation from the default enums group', () => {
    const label = enumLabel('SEDAN', i18n.t.bind(i18n));
    expect(label).toBe('Sedan');
  });

  it('resolves values from a named group', () => {
    expect(enumLabel('STREET_PARKING', i18n.t.bind(i18n), ['parkingContext'])).toBe(
      'Street parking',
    );
    expect(enumLabel('ACTIVE', i18n.t.bind(i18n), ['spotStatus'])).toBe('Active');
    expect(enumLabel('fresh', i18n.t.bind(i18n), ['freshness'])).toBe('Fresh');
  });

  it('falls back to unknownEnum for unknown values when localized', () => {
    expect(enumLabel('TOTALLY_FAKE_ENUM', i18n.t.bind(i18n))).toBe('Unknown');
  });

  it('humanizeEnum still produces English title case as a last-resort helper', () => {
    expect(humanizeEnum('STREET_PARKING')).toBe('Street parking');
  });
});

describe('enumLabel Turkish', () => {
  it('prefers localized unknown over English humanize', async () => {
    await i18n.changeLanguage('tr');
    expect(enumLabel('TOTALLY_FAKE_ENUM', i18n.t.bind(i18n))).toBe('Bilinmiyor');
    expect(enumLabel('ACTIVE', i18n.t.bind(i18n), ['spotStatus'])).toBe('Aktif');
    expect(enumLabel('FILLED', i18n.t.bind(i18n), ['spotStatus'])).toBe('Dolu');
    expect(enumLabel('fresh', i18n.t.bind(i18n), ['freshness'])).toBe('Yeni');
    await i18n.changeLanguage('en');
  });
});