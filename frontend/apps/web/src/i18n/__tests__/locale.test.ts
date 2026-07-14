import { describe, expect, it, beforeAll } from 'vitest';
import { DEFAULT_LOCALE, LOCALE_STORAGE_KEY } from '@parkio/types';
import { initI18n } from '@/i18n';
import { readStoredLocale, resolveInitialLocale, writeStoredLocale } from '@/i18n/locale-storage';
import { useLocaleStore } from '@/i18n/localeStore';
import { formatRelativeAgo } from '@/lib/format';

describe('i18n locale resolution', () => {
  beforeAll(async () => {
    localStorage.clear();
    await initI18n('tr');
  });

  it('defaults to Turkish when no preference exists', () => {
    localStorage.clear();
    expect(resolveInitialLocale()).toBe('tr');
  });

  it('persists explicit locale in localStorage', () => {
    writeStoredLocale('en');
    expect(readStoredLocale()).toBe('en');
    expect(localStorage.getItem(LOCALE_STORAGE_KEY)).toBe('en');
  });

  it('rejects unsupported locale values', () => {
    localStorage.setItem(LOCALE_STORAGE_KEY, 'fr');
    expect(resolveInitialLocale()).toBe(DEFAULT_LOCALE);
  });

  it('sets html lang attribute', async () => {
    await initI18n('en');
    expect(document.documentElement.lang).toBe('en');
    await initI18n('tr');
    expect(document.documentElement.lang).toBe('tr');
  });

  it('switches locale immediately via store', async () => {
    await initI18n('tr');
    useLocaleStore.getState().setLocale('en');
    expect(useLocaleStore.getState().locale).toBe('en');
    expect(document.documentElement.lang).toBe('en');
  });

  it('formats relative time by locale', async () => {
    const iso = new Date(Date.now() - 5 * 60_000).toISOString();
    await initI18n('tr');
    expect(formatRelativeAgo(iso, 'tr')).toMatch(/dk|sa|gün|önce/);
    await initI18n('en');
    expect(formatRelativeAgo(iso, 'en')).toMatch(/m ago|h ago|d ago|minute/i);
  });
});