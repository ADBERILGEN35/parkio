import type { ParkioLocale } from '@parkio/types';

/** Parse `lang` / similar query values into an allowlisted Parkio locale. */
export function localeFromSearchParam(raw: string | null | undefined): ParkioLocale | null {
  if (!raw) return null;
  const normalized = raw.trim().toLowerCase();
  if (normalized === 'en' || normalized === 'tr') return normalized;
  return null;
}
