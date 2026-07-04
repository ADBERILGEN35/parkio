import type { SmartReturnSettings } from '@parkio/types';
import {
  FALLBACK_RETURN_TIME,
  checkTimeFromIso,
  checkTimeFromValue,
  formatClock,
  initialReturnTime,
  isValidTimeValue,
  stepTime,
  toTimeValue,
  todayAt,
} from '../time';

function settingsWith(overrides: Partial<SmartReturnSettings>): SmartReturnSettings {
  return {
    enabled: true,
    homeLatitude: 38.42,
    homeLongitude: 27.13,
    homeLabel: 'Alsancak, İzmir',
    defaultReturnTime: null,
    reminderLeadMinutes: 30,
    lastPromptDate: null,
    todayStatus: 'UNKNOWN',
    todayExpectedReturnAt: null,
    todayReturnCheckCompletedAt: null,
    todayNotificationSentAt: null,
    ...overrides,
  };
}

/** Locale-formatted clock for a local date — the same call the helpers make. */
function clock(date: Date): string {
  return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

describe('todayAt / toTimeValue', () => {
  it('builds today at the given local time', () => {
    const at = todayAt('18:30');
    expect(at).not.toBeNull();
    expect(at!.getHours()).toBe(18);
    expect(at!.getMinutes()).toBe(30);
    const now = new Date();
    expect(at!.getFullYear()).toBe(now.getFullYear());
    expect(at!.getDate()).toBe(now.getDate());
  });

  it('returns null for malformed values', () => {
    expect(todayAt('half past six')).toBeNull();
    expect(todayAt('')).toBeNull();
  });

  it('round-trips through toTimeValue', () => {
    expect(toTimeValue(todayAt('07:05')!)).toBe('07:05');
  });
});

describe('isValidTimeValue', () => {
  it.each(['00:00', '23:59', '18:30'])('accepts %s', (value) => {
    expect(isValidTimeValue(value)).toBe(true);
  });
  it.each(['24:00', '18:60', '7:30', 'abc'])('rejects %s', (value) => {
    expect(isValidTimeValue(value)).toBe(false);
  });
});

describe('check-time math', () => {
  it('subtracts the lead from an ISO return instant', () => {
    const returnAt = todayAt('18:30')!;
    const expected = new Date(returnAt);
    expected.setMinutes(expected.getMinutes() - 45);
    expect(checkTimeFromIso(returnAt.toISOString(), 45)).toBe(clock(expected));
  });

  it('previews the check time from an HH:mm value', () => {
    const expected = todayAt('18:00')!;
    expect(checkTimeFromValue('18:30', 30)).toBe(clock(expected));
  });

  it('returns null for a malformed preview value', () => {
    expect(checkTimeFromValue('bogus', 30)).toBeNull();
  });

  it('formatClock renders the local clock of an ISO instant', () => {
    const at = todayAt('09:15')!;
    expect(formatClock(at.toISOString())).toBe(clock(at));
  });
});

describe('initialReturnTime', () => {
  it("prefers today's saved plan", () => {
    const at = todayAt('20:10')!;
    const settings = settingsWith({
      todayExpectedReturnAt: at.toISOString(),
      defaultReturnTime: '18:30',
    });
    expect(initialReturnTime(settings)).toBe('20:10');
  });

  it('falls back to the default return time, then the web fallback', () => {
    expect(initialReturnTime(settingsWith({ defaultReturnTime: '17:45' }))).toBe('17:45');
    expect(initialReturnTime(settingsWith({}))).toBe(FALLBACK_RETURN_TIME);
  });
});

describe('stepTime', () => {
  it('steps hours and wraps within the day', () => {
    expect(stepTime('18:30', 'hour', 1)).toBe('19:30');
    expect(stepTime('23:30', 'hour', 1)).toBe('00:30');
    expect(stepTime('00:30', 'hour', -1)).toBe('23:30');
  });

  it('steps minutes on a snapped grid and wraps within the hour', () => {
    expect(stepTime('18:30', 'minute', 5)).toBe('18:35');
    expect(stepTime('18:30', 'minute', -5)).toBe('18:25');
    expect(stepTime('18:58', 'minute', 5)).toBe('18:00');
    expect(stepTime('18:02', 'minute', -5)).toBe('18:00');
    expect(stepTime('18:00', 'minute', -5)).toBe('18:55');
  });
});
