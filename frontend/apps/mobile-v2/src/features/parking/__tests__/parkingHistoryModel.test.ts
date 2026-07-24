import {
  filterTerminalHistoryItems,
  formatTerminalSessionDuration,
  isTerminalParkingSessionStatus,
  terminalSessionDurationMs,
} from '../parkingHistoryModel';
import type { ParkingSessionResponse } from '@parkio/types';

const completed: ParkingSessionResponse = {
  id: 'd431ad5a-f8ce-4be2-b4dc-248b47990b39',
  status: 'COMPLETED',
  parkingSource: 'MANUAL',
  startedAt: '2026-07-21T09:00:00Z',
  endedAt: '2026-07-21T10:30:00Z',
  latitude: 41.0082,
  longitude: 28.9784,
  estimatedFee: null,
};

describe('parkingHistoryModel', () => {
  it('recognizes only COMPLETED and CANCELLED as terminal', () => {
    expect(isTerminalParkingSessionStatus('COMPLETED')).toBe(true);
    expect(isTerminalParkingSessionStatus('CANCELLED')).toBe(true);
    expect(isTerminalParkingSessionStatus('ACTIVE')).toBe(false);
  });

  it('filters ACTIVE and duplicate ids from history pages', () => {
    const active = { ...completed, id: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', status: 'ACTIVE' as const };
    const dup = { ...completed };
    const cancelled = {
      ...completed,
      id: 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
      status: 'CANCELLED' as const,
    };
    expect(filterTerminalHistoryItems([active, completed, dup, cancelled])).toEqual([
      completed,
      cancelled,
    ]);
  });

  it('derives duration from startedAt/endedAt without using now', () => {
    expect(terminalSessionDurationMs(completed.startedAt, completed.endedAt)).toBe(5_400_000);
    expect(formatTerminalSessionDuration(completed.startedAt, completed.endedAt, 'en')).toContain('h');
  });

  it('fails closed for missing, invalid, or negative durations', () => {
    expect(terminalSessionDurationMs(completed.startedAt, null)).toBeNull();
    expect(terminalSessionDurationMs('bad', completed.endedAt)).toBeNull();
    expect(terminalSessionDurationMs('2026-07-21T12:00:00Z', '2026-07-21T11:00:00Z')).toBeNull();
    expect(formatTerminalSessionDuration(completed.startedAt, null, 'tr')).toBe('');
  });
});
