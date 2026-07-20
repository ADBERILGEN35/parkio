import {
  formatCountdown,
  formatDistance,
  formatShortDuration,
  parseTimeOfDay,
  remainingFraction,
  remainingMs,
} from '../time';

describe('formatCountdown', () => {
  it('renders mm:ss with zero padding', () => {
    expect(formatCountdown(7 * 60_000 + 42_000)).toBe('07:42');
    expect(formatCountdown(0)).toBe('00:00');
    expect(formatCountdown(-5000)).toBe('00:00');
  });

  it('adds hours above one hour', () => {
    expect(formatCountdown(3_600_000 + 61_000)).toBe('1:01:01');
  });
});

describe('remainingFraction', () => {
  const createdAt = '2026-07-18T10:00:00.000Z';
  const expiresAt = '2026-07-18T10:10:00.000Z';

  it('is 1 at creation and 0 at expiry', () => {
    expect(remainingFraction(createdAt, expiresAt, Date.parse(createdAt))).toBe(1);
    expect(remainingFraction(createdAt, expiresAt, Date.parse(expiresAt))).toBe(0);
  });

  it('is 0.5 at half life and clamps beyond bounds', () => {
    const half = Date.parse(createdAt) + 5 * 60_000;
    expect(remainingFraction(createdAt, expiresAt, half)).toBeCloseTo(0.5);
    expect(remainingFraction(createdAt, expiresAt, Date.parse(expiresAt) + 999)).toBe(0);
  });

  it('fails safe on malformed input', () => {
    expect(remainingFraction('garbage', expiresAt, Date.now())).toBe(0);
    expect(remainingFraction(expiresAt, createdAt, Date.now())).toBe(0);
  });
});

describe('remainingMs', () => {
  it('never goes negative', () => {
    expect(remainingMs('2000-01-01T00:00:00.000Z', Date.now())).toBe(0);
  });
});

describe('formatShortDuration', () => {
  it('formats minutes and hours per locale', () => {
    expect(formatShortDuration(3 * 60_000, 'tr')).toBe('3 dk');
    expect(formatShortDuration(3 * 60_000, 'en')).toBe('3 min');
    expect(formatShortDuration(90 * 60_000, 'tr')).toBe('1 sa 30 dk');
    expect(formatShortDuration(120 * 60_000, 'en')).toBe('2 h');
  });
});

describe('formatDistance', () => {
  it('uses meters below 1 km and locale decimal above', () => {
    expect(formatDistance(350, 'tr')).toBe('350 m');
    expect(formatDistance(1200, 'tr')).toBe('1,2 km');
    expect(formatDistance(1200, 'en')).toBe('1.2 km');
    expect(formatDistance(12_400, 'en')).toBe('12 km');
  });
});

describe('parseTimeOfDay', () => {
  it('accepts HH:mm and HH:mm:ss', () => {
    expect(parseTimeOfDay('18:30')).toEqual({ hours: 18, minutes: 30 });
    expect(parseTimeOfDay('18:30:00')).toEqual({ hours: 18, minutes: 30 });
    expect(parseTimeOfDay('8:05')).toEqual({ hours: 8, minutes: 5 });
  });

  it('rejects invalid values', () => {
    expect(parseTimeOfDay('24:00')).toBeNull();
    expect(parseTimeOfDay('18:61')).toBeNull();
    expect(parseTimeOfDay('nope')).toBeNull();
  });
});
