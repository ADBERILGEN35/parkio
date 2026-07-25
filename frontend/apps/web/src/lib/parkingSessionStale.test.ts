import { describe, expect, it } from 'vitest';
import { needsActiveConfirmation } from './parkingSessionStale';

const CONFIRM_AFTER_MS = 24 * 60 * 60 * 1000;

const base = {
  status: 'ACTIVE' as const,
  startedAt: '2026-07-21T09:00:00.000Z',
  lastConfirmedAt: '2026-07-21T09:00:00.000Z',
};

describe('needsActiveConfirmation', () => {
  it('is false before confirm-after and true at/after the configured threshold', () => {
    const start = Date.parse(base.startedAt);
    expect(needsActiveConfirmation(base, CONFIRM_AFTER_MS, start + CONFIRM_AFTER_MS - 1)).toBe(
      false,
    );
    expect(needsActiveConfirmation(base, CONFIRM_AFTER_MS, start + CONFIRM_AFTER_MS)).toBe(true);
  });

  it('uses lastConfirmedAt when present', () => {
    const confirmed = '2026-07-22T09:00:00.000Z';
    const confirmedMs = Date.parse(confirmed);
    expect(
      needsActiveConfirmation(
        { ...base, lastConfirmedAt: confirmed },
        CONFIRM_AFTER_MS,
        confirmedMs + CONFIRM_AFTER_MS - 1,
      ),
    ).toBe(false);
    expect(
      needsActiveConfirmation(
        { ...base, lastConfirmedAt: confirmed },
        CONFIRM_AFTER_MS,
        confirmedMs + CONFIRM_AFTER_MS,
      ),
    ).toBe(true);
  });

  it('falls back to startedAt when lastConfirmedAt is null', () => {
    const start = Date.parse(base.startedAt);
    expect(
      needsActiveConfirmation(
        { ...base, lastConfirmedAt: null },
        CONFIRM_AFTER_MS,
        start + CONFIRM_AFTER_MS,
      ),
    ).toBe(true);
  });

  it('ignores non-ACTIVE sessions', () => {
    expect(
      needsActiveConfirmation(
        { ...base, status: 'COMPLETED' },
        CONFIRM_AFTER_MS,
        Date.parse(base.startedAt) + CONFIRM_AFTER_MS * 2,
      ),
    ).toBe(false);
  });

  it('honors a non-default confirm-after threshold from config', () => {
    const start = Date.parse(base.startedAt);
    const twelveHours = 12 * 60 * 60 * 1000;
    expect(needsActiveConfirmation(base, twelveHours, start + twelveHours - 1)).toBe(false);
    expect(needsActiveConfirmation(base, twelveHours, start + twelveHours)).toBe(true);
  });
});
