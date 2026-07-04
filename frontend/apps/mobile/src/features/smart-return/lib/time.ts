import type { SmartReturnSettings } from '@parkio/types';

/**
 * Pure HH:mm / ISO time helpers for the Smart Return flows. Mirrors the web
 * SmartReturnCard's time math so both apps show the same clock and "we'll
 * check around" preview for the same settings.
 */

/** Web fallback when neither today's plan nor a default return time exists. */
export const FALLBACK_RETURN_TIME = '18:30';

const TIME_VALUE = /^([01]\d|2[0-3]):[0-5]\d$/;

export function isValidTimeValue(value: string): boolean {
  return TIME_VALUE.test(value);
}

/** Today's date at the given local HH:mm, or null for a malformed value. */
export function todayAt(time: string): Date | null {
  const [hh, mm] = time.split(':').map(Number);
  if (!Number.isInteger(hh) || !Number.isInteger(mm)) return null;
  const date = new Date();
  date.setHours(hh, mm, 0, 0);
  return date;
}

export function toTimeValue(date: Date): string {
  return `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;
}

export function formatClock(iso: string): string {
  return new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

/** Clock time of the availability check for a saved ISO return instant. */
export function checkTimeFromIso(expectedReturnAt: string, leadMinutes: number): string {
  const checkAt = new Date(expectedReturnAt);
  checkAt.setMinutes(checkAt.getMinutes() - leadMinutes);
  return checkAt.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

/** Live preview of the check time from an HH:mm picker value (today, local). */
export function checkTimeFromValue(time: string, leadMinutes: number): string | null {
  const at = todayAt(time);
  if (!at) return null;
  at.setMinutes(at.getMinutes() - leadMinutes);
  return at.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

/** Picker starting value: today's saved plan, else the default, else 18:30. */
export function initialReturnTime(settings: SmartReturnSettings): string {
  if (settings.todayExpectedReturnAt) {
    return toTimeValue(new Date(settings.todayExpectedReturnAt));
  }
  return settings.defaultReturnTime ?? FALLBACK_RETURN_TIME;
}

/**
 * Step an HH:mm value by whole hours or minutes, wrapping within the day.
 * Minute steps snap to multiples of |delta| so chevron taps land on tidy values.
 */
export function stepTime(value: string, part: 'hour' | 'minute', delta: number): string {
  const at = todayAt(value) ?? todayAt(FALLBACK_RETURN_TIME)!;
  let hours = at.getHours();
  let minutes = at.getMinutes();
  if (part === 'hour') {
    hours = (hours + delta + 24) % 24;
  } else {
    const step = Math.abs(delta);
    const snapped = delta > 0 ? Math.floor(minutes / step) * step : Math.ceil(minutes / step) * step;
    minutes = (snapped + delta + 60) % 60;
  }
  return `${String(hours).padStart(2, '0')}:${String(minutes).padStart(2, '0')}`;
}
