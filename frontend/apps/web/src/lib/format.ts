import type { ParkioLocale } from '@parkio/types';
import { DEFAULT_LOCALE } from '@parkio/types';
import i18n from 'i18next';
import { INTL_LOCALE } from '@/i18n/config';

function resolveLocale(locale?: ParkioLocale): ParkioLocale {
  return locale ?? (i18n.language as ParkioLocale) ?? DEFAULT_LOCALE;
}

/** Formats a backend ISO instant for display; falls back to the raw value. */
export function formatInstant(value: string, locale?: ParkioLocale): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString(INTL_LOCALE[resolveLocale(locale)]);
}

/** `STREET_PARKING` → `Street parking`. Tolerant of unknown enum values. */
export function humanizeEnum(value: string): string {
  const words = value.toLowerCase().split('_').join(' ');
  return words.charAt(0).toUpperCase() + words.slice(1);
}

type EnumTranslate = (key: string, options?: Record<string, unknown>) => string;

/**
 * Looks up a backend enum under one or more `common` groups (default `enums`),
 * then a localized generic unknown, then English humanize as last resort.
 */
export function enumLabel(
  value: string,
  t: EnumTranslate,
  groups: string[] = ['enums'],
): string {
  for (const group of groups) {
    const key = `${group}.${value}`;
    const translated = t(key, { ns: 'common', defaultValue: '' });
    if (translated && translated !== key && translated !== '') return translated;
  }
  const unknown = t('unknownEnum', { ns: 'common', defaultValue: '' });
  if (unknown) return unknown;
  return humanizeEnum(value);
}

/**
 * Compact relative age for a past instant.
 * Uses `Intl.RelativeTimeFormat` for minute/hour/day units and i18n for "just now".
 */
export function formatRelativeAgo(
  value: string,
  locale?: ParkioLocale,
  now: Date = new Date(),
): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  const lng = resolveLocale(locale);
  const minutes = Math.max(0, Math.floor((now.getTime() - date.getTime()) / 60_000));
  if (minutes < 1) {
    return i18n.t('common:relative.justNow', { lng });
  }
  const rtf = new Intl.RelativeTimeFormat(INTL_LOCALE[lng], { numeric: 'always', style: 'narrow' });
  if (minutes < 60) return rtf.format(-minutes, 'minute');
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return rtf.format(-hours, 'hour');
  return rtf.format(-Math.floor(hours / 24), 'day');
}

/** Remaining validity until an instant: localized “left” / “Expired”. */
export function formatRemaining(
  value: string,
  locale?: ParkioLocale,
  now: Date = new Date(),
): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  const lng = resolveLocale(locale);
  const minutes = Math.floor((date.getTime() - now.getTime()) / 60_000);
  if (minutes <= 0) {
    return i18n.t('common:relative.expired', { lng });
  }
  if (minutes < 60) {
    return i18n.t('common:relative.minutesLeft', { lng, count: minutes });
  }
  const hours = Math.floor(minutes / 60);
  if (hours < 24) {
    return i18n.t('common:relative.hoursLeft', { lng, count: hours });
  }
  return i18n.t('common:relative.daysLeft', { lng, count: Math.floor(hours / 24) });
}
