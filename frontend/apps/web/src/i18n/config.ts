import type { ParkioLocale } from '@parkio/types';
import { DEFAULT_LOCALE, SUPPORTED_LOCALES } from '@parkio/types';

export const FALLBACK_LOCALE: ParkioLocale = DEFAULT_LOCALE;

/** BCP-47 tags used with Intl formatters. */
export const INTL_LOCALE: Record<ParkioLocale, string> = {
  tr: 'tr-TR',
  en: 'en-US',
};

export const I18N_NAMESPACES = [
  'common',
  'auth',
  'navigation',
  'settings',
  'map',
  'parking',
  'media',
  'moderation',
  'analytics',
  'admin',
  'errors',
  'validation',
  'legal',
  'explore',
] as const;

export type I18nNamespace = (typeof I18N_NAMESPACES)[number];

export const DEFAULT_NS: I18nNamespace = 'common';

export function isSupportedLocale(value: unknown): value is ParkioLocale {
  return typeof value === 'string' && (SUPPORTED_LOCALES as readonly string[]).includes(value);
}
