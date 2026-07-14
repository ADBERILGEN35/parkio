import { DEFAULT_LOCALE, LOCALE_STORAGE_KEY, normalizeLocale, type ParkioLocale } from '@parkio/types';

export function readStoredLocale(): ParkioLocale | null {
  if (typeof window === 'undefined') return null;
  try {
    const raw = window.localStorage.getItem(LOCALE_STORAGE_KEY);
    if (!raw) return null;
    return normalizeLocale(raw);
  } catch {
    return null;
  }
}

export function writeStoredLocale(locale: ParkioLocale): void {
  if (typeof window === 'undefined') return;
  try {
    window.localStorage.setItem(LOCALE_STORAGE_KEY, locale);
  } catch {
    // ignore quota / privacy mode
  }
}

export function resolveInitialLocale(explicit?: ParkioLocale | null): ParkioLocale {
  return explicit ?? readStoredLocale() ?? DEFAULT_LOCALE;
}

export function applyDocumentLocale(locale: ParkioLocale): void {
  if (typeof document === 'undefined') return;
  document.documentElement.lang = locale;
}
