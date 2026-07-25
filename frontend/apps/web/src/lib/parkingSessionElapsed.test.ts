import { describe, expect, it } from 'vitest';
import {
  elapsedMsFromStartedAt,
  formatCountdown,
  formatElapsedFromStartedAt,
} from './parkingSessionElapsed';

describe('parkingSessionElapsed', () => {
  it('formats countdowns as mm:ss and h:mm:ss', () => {
    expect(formatCountdown(0)).toBe('00:00');
    expect(formatCountdown(65_000)).toBe('01:05');
    expect(formatCountdown(3_661_000)).toBe('1:01:01');
  });

  it('clamps negative elapsed and fails closed on invalid startedAt', () => {
    const now = Date.parse('2026-07-25T12:00:00.000Z');
    expect(elapsedMsFromStartedAt('2026-07-25T13:00:00.000Z', now)).toBe(0);
    expect(elapsedMsFromStartedAt('not-a-date', now)).toBeNull();
    expect(formatElapsedFromStartedAt('not-a-date', now)).toBe('00:00');
  });

  it('formats elapsed from a valid startedAt', () => {
    const now = Date.parse('2026-07-25T12:05:30.000Z');
    expect(formatElapsedFromStartedAt('2026-07-25T12:00:00.000Z', now)).toBe('05:30');
  });
});