import { describe, expect, it } from 'vitest';
import {
  flattenTerminalHistoryPages,
  isTerminalParkingSessionStatus,
  terminalDurationParts,
  terminalSessionDurationMs,
} from './parkingSessionHistoryFormat';

const base = {
  parkingSource: 'MANUAL' as const,
  latitude: 38.42,
  longitude: 27.14,
  estimatedFee: null,
};

describe('parkingSessionHistoryFormat', () => {
  it('classifies terminal statuses', () => {
    expect(isTerminalParkingSessionStatus('COMPLETED')).toBe(true);
    expect(isTerminalParkingSessionStatus('CANCELLED')).toBe(true);
    expect(isTerminalParkingSessionStatus('ACTIVE')).toBe(false);
    expect(isTerminalParkingSessionStatus('WEIRD')).toBe(false);
  });

  it('computes non-negative duration and rejects malformed/missing ends', () => {
    expect(
      terminalSessionDurationMs('2026-07-25T10:00:00.000Z', '2026-07-25T11:30:00.000Z'),
    ).toBe(5_400_000);
    expect(terminalSessionDurationMs('2026-07-25T10:00:00.000Z', null)).toBeNull();
    expect(terminalSessionDurationMs('not-a-date', '2026-07-25T11:00:00.000Z')).toBeNull();
    expect(
      terminalSessionDurationMs('2026-07-25T12:00:00.000Z', '2026-07-25T11:00:00.000Z'),
    ).toBeNull();
  });

  it('exposes hour/minute parts without a live clock', () => {
    expect(
      terminalDurationParts('2026-07-25T10:00:00.000Z', '2026-07-25T11:05:00.000Z'),
    ).toEqual({ hours: 1, minutes: 5 });
    expect(
      terminalDurationParts('2026-07-25T10:00:00.000Z', '2026-07-25T10:00:30.000Z'),
    ).toEqual({ hours: 0, minutes: 0 });
  });

  it('flattens pages, drops duplicates, and excludes ACTIVE/unknown', () => {
    const completed = {
      id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      status: 'COMPLETED' as const,
      startedAt: '2026-07-25T10:00:00.000Z',
      endedAt: '2026-07-25T11:00:00.000Z',
      ...base,
    };
    const cancelled = {
      id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
      status: 'CANCELLED' as const,
      startedAt: '2026-07-24T10:00:00.000Z',
      endedAt: '2026-07-24T11:00:00.000Z',
      ...base,
    };
    const active = {
      id: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
      status: 'ACTIVE' as const,
      startedAt: '2026-07-25T12:00:00.000Z',
      endedAt: null,
      ...base,
    };
    const out = flattenTerminalHistoryPages([
      { items: [completed, active] },
      { items: [completed, cancelled] },
    ]);
    expect(out.map((s) => s.id)).toEqual([completed.id, cancelled.id]);
  });
});