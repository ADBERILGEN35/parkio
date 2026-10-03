import { describe, expect, it } from 'vitest';
import { fromLocalDateTimeInput, toLocalDateTimeInput } from './localDateTimeInput';

describe('localDateTimeInput', () => {
  it('shows an instant as local wall time, not as its UTC digits', () => {
    // UTC+3 (Istanbul): getTimezoneOffset() is -180.
    expect(toLocalDateTimeInput('2026-10-03T07:00:00.000Z', -180)).toBe('2026-10-03T10:00');
    // UTC-5: getTimezoneOffset() is 300.
    expect(toLocalDateTimeInput('2026-10-03T07:00:00.000Z', 300)).toBe('2026-10-03T02:00');
    expect(toLocalDateTimeInput('2026-10-03T07:00:00.000Z', 0)).toBe('2026-10-03T07:00');
  });

  it('round-trips what the user typed in the runtime time zone', () => {
    const typed = '2026-09-01T00:00';
    const iso = fromLocalDateTimeInput(typed);

    expect(iso).toBe(new Date(typed).toISOString());
    expect(toLocalDateTimeInput(iso)).toBe(typed);
  });

  it('treats empty and invalid values as no filter', () => {
    expect(toLocalDateTimeInput('')).toBe('');
    expect(toLocalDateTimeInput('not a date')).toBe('');
    expect(fromLocalDateTimeInput('')).toBe('');
    expect(fromLocalDateTimeInput('not a date')).toBe('');
  });
});
