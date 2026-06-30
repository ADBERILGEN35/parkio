import { describe, expect, it } from 'vitest';
import { formatDistance, formatRelativeTime, haversineMeters } from './distance';

describe('haversineMeters', () => {
  it('is zero for identical points', () => {
    expect(haversineMeters({ lat: 38.4237, lng: 27.1428 }, { lat: 38.4237, lng: 27.1428 })).toBe(0);
  });

  it('approximates a known short distance (İzmir ~1km)', () => {
    // ~0.009deg latitude ≈ 1 km
    const d = haversineMeters({ lat: 38.4237, lng: 27.1428 }, { lat: 38.4327, lng: 27.1428 });
    expect(d).toBeGreaterThan(950);
    expect(d).toBeLessThan(1050);
  });

  it('is symmetric', () => {
    const a = { lat: 38.4, lng: 27.1 };
    const b = { lat: 38.5, lng: 27.2 };
    expect(haversineMeters(a, b)).toBeCloseTo(haversineMeters(b, a), 6);
  });
});

describe('formatDistance', () => {
  it('uses meters under 1km', () => {
    expect(formatDistance(0)).toBe('0 m');
    expect(formatDistance(120.4)).toBe('120 m');
    expect(formatDistance(999)).toBe('999 m');
  });

  it('uses one decimal km between 1 and 10', () => {
    expect(formatDistance(1400)).toBe('1.4 km');
    expect(formatDistance(9990)).toBe('10.0 km');
  });

  it('rounds km at/above 10', () => {
    expect(formatDistance(12000)).toBe('12 km');
    expect(formatDistance(12600)).toBe('13 km');
  });
});

describe('formatRelativeTime', () => {
  const now = Date.parse('2026-06-30T12:00:00Z');

  it('reports just now within 45s', () => {
    expect(formatRelativeTime('2026-06-30T11:59:30Z', now)).toBe('just now');
  });

  it('reports minutes / hours / days', () => {
    expect(formatRelativeTime('2026-06-30T11:55:00Z', now)).toBe('5 min ago');
    expect(formatRelativeTime('2026-06-30T09:00:00Z', now)).toBe('3 h ago');
    expect(formatRelativeTime('2026-06-28T12:00:00Z', now)).toBe('2 d ago');
  });

  it('returns empty string for an invalid date', () => {
    expect(formatRelativeTime('not-a-date', now)).toBe('');
  });
});
