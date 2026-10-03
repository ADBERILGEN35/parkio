/**
 * A `datetime-local` input shows and returns local wall time without a zone, while the API
 * takes ISO instants. Showing the instant's UTC digits in that input would shift the value by
 * the browser's offset, so both directions convert explicitly.
 */

/** ISO instant -> `YYYY-MM-DDTHH:mm` in local time ('' for empty or invalid input). */
export function toLocalDateTimeInput(iso: string, offsetMinutes?: number): string {
  if (!iso) return '';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '';
  const offset = offsetMinutes ?? date.getTimezoneOffset();
  return new Date(date.getTime() - offset * 60_000).toISOString().slice(0, 16);
}

/** `datetime-local` value (local wall time) -> ISO instant ('' for empty or invalid input). */
export function fromLocalDateTimeInput(value: string): string {
  if (!value) return '';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '' : date.toISOString();
}
