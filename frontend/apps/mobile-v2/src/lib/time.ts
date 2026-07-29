import type { Translator } from '@/i18n/LocaleProvider';

/**
 * Time material for "The Living Signal": countdowns, freshness fractions and
 * relative labels. All numbers render with tabular figures at the call site.
 */

/** mm:ss (or h:mm:ss above an hour) — tabular, zero-padded. */
export function formatCountdown(remainingMs: number): string {
  const total = Math.max(0, Math.floor(remainingMs / 1000));
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const seconds = total % 60;
  const mm = String(minutes).padStart(2, '0');
  const ss = String(seconds).padStart(2, '0');
  return hours > 0 ? `${hours}:${mm}:${ss}` : `${mm}:${ss}`;
}

/** Remaining lifetime fraction in [0,1] for the freshness ring. */
export function remainingFraction(createdAt: string, expiresAt: string, now: number): number {
  const created = Date.parse(createdAt);
  const expires = Date.parse(expiresAt);
  if (!Number.isFinite(created) || !Number.isFinite(expires) || expires <= created) {
    return 0;
  }
  const fraction = (expires - now) / (expires - created);
  return Math.min(1, Math.max(0, fraction));
}

export function remainingMs(expiresAt: string, now: number): number {
  const expires = Date.parse(expiresAt);
  return Number.isFinite(expires) ? Math.max(0, expires - now) : 0;
}

/** "3 dk" / "1 sa 12 dk" style short relative durations. */
export function formatShortDuration(ms: number, locale: 'tr' | 'en'): string {
  const totalMinutes = Math.max(0, Math.round(ms / 60_000));
  const minuteUnit = locale === 'tr' ? 'dk' : 'min';
  if (totalMinutes < 60) {
    return `${totalMinutes} ${minuteUnit}`;
  }
  const hours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes % 60;
  const hourUnit = locale === 'tr' ? 'sa' : 'h';
  return minutes > 0 ? `${hours} ${hourUnit} ${minutes} ${minuteUnit}` : `${hours} ${hourUnit}`;
}

/** "Az önce paylaşıldı" / "3 dk önce paylaşıldı" freshness line. */
export function formatSharedAgo(
  createdAt: string,
  now: number,
  locale: 'tr' | 'en',
  t: Translator,
): string {
  const created = Date.parse(createdAt);
  if (!Number.isFinite(created)) {
    return '';
  }
  const elapsed = now - created;
  if (elapsed < 60_000) {
    return t('spot.justShared');
  }
  return t('spot.sharedAgo', { time: formatShortDuration(elapsed, locale) });
}

/** 24-hour clock "14:05". */
export function formatClock(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return '';
  }
  const hh = String(date.getHours()).padStart(2, '0');
  const mm = String(date.getMinutes()).padStart(2, '0');
  return `${hh}:${mm}`;
}

/** "17.07.2026" date for member-since / ledger rows. */
export function formatDate(iso: string, locale: 'tr' | 'en'): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return '';
  }
  return date.toLocaleDateString(locale === 'tr' ? 'tr-TR' : 'en-GB', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
  });
}

/** Local date + time for rejection / audit timestamps. */
export function formatDateTime(iso: string, locale: 'tr' | 'en'): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return '';
  }
  return date.toLocaleString(locale === 'tr' ? 'tr-TR' : 'en-GB', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

/** Relative "x dk önce / x min ago" for notification rows. */
export function formatRelative(iso: string, now: number, locale: 'tr' | 'en'): string {
  const then = Date.parse(iso);
  if (!Number.isFinite(then)) {
    return '';
  }
  const elapsed = Math.max(0, now - then);
  if (elapsed < 60_000) {
    return locale === 'tr' ? 'şimdi' : 'now';
  }
  if (elapsed < 24 * 3_600_000) {
    const dur = formatShortDuration(elapsed, locale);
    return locale === 'tr' ? `${dur} önce` : `${dur} ago`;
  }
  return formatDate(iso, locale);
}

/** Meters → "350 m" / "1,2 km". */
export function formatDistance(meters: number, locale: 'tr' | 'en'): string {
  if (!Number.isFinite(meters)) {
    return '';
  }
  if (meters < 1000) {
    return `${Math.round(meters)} m`;
  }
  const km = meters / 1000;
  const rounded = km >= 10 ? Math.round(km).toString() : km.toFixed(1);
  return `${locale === 'tr' ? rounded.replace('.', ',') : rounded} km`;
}

/** "HH:mm" (backend LocalTime also sends "HH:mm:ss") → hours/minutes. */
export function parseTimeOfDay(value: string): { hours: number; minutes: number } | null {
  const match = /^(\d{1,2}):(\d{2})(?::\d{2})?$/.exec(value.trim());
  if (!match) {
    return null;
  }
  const hours = Number(match[1]);
  const minutes = Number(match[2]);
  if (hours > 23 || minutes > 59) {
    return null;
  }
  return { hours, minutes };
}
