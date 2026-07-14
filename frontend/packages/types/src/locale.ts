/** Supported UI locales. Turkish is the canonical default. */
export const SUPPORTED_LOCALES = ['tr', 'en'] as const;

export type ParkioLocale = (typeof SUPPORTED_LOCALES)[number];

export const DEFAULT_LOCALE: ParkioLocale = 'tr';

export const LOCALE_STORAGE_KEY = 'parkio.locale';

export function isParkioLocale(value: unknown): value is ParkioLocale {
  return typeof value === 'string' && (SUPPORTED_LOCALES as readonly string[]).includes(value);
}

/** Coerce unknown input to a supported locale; falls back to Turkish. */
export function normalizeLocale(value: unknown): ParkioLocale {
  return isParkioLocale(value) ? value : DEFAULT_LOCALE;
}
