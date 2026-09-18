import { describe, expect, it } from 'vitest';
import type { ParkingSessionResponse } from '@parkio/types';
import {
  isUsableParkedCarCoordinate,
  municipalParkTarget,
  shouldRecordRecentParking,
  toParkedCarView,
  toRecordRecentParkingRequest,
} from './parked-car';

const activeSession = {
  id: '11111111-1111-4111-8111-111111111111',
  status: 'ACTIVE',
  parkingSource: 'MANUAL',
  startedAt: '2026-08-07T07:00:00.000Z',
  endedAt: null,
  latitude: 38.42,
  longitude: 27.14,
  estimatedFee: null,
  lastConfirmedAt: '2026-08-07T07:00:00.000Z',
  completionType: null,
} satisfies ParkingSessionResponse;

describe('parked-car view', () => {
  it('rejects unusable coordinates', () => {
    expect(isUsableParkedCarCoordinate(NaN, 27)).toBe(false);
    expect(isUsableParkedCarCoordinate(38, 200)).toBe(false);
  });

  it('builds ACTIVE view from session', () => {
    const view = toParkedCarView(activeSession, {
      nowMs: Date.parse('2026-08-07T07:45:00.000Z'),
    });
    expect(view).toMatchObject({
      sessionId: activeSession.id,
      lifecycle: 'ACTIVE',
      latitude: 38.42,
      longitude: 27.14,
      returnAvailable: true,
      elapsedMinutes: 45,
    });
  });

  it('returns null for non-ACTIVE or invalid coords', () => {
    expect(toParkedCarView({ ...activeSession, status: 'COMPLETED' })).toBeNull();
    expect(toParkedCarView({ ...activeSession, latitude: 999 })).toBeNull();
    expect(toParkedCarView(null)).toBeNull();
  });

  it('records RecentParking only for municipal targets', () => {
    const municipal = municipalParkTarget('fac-1', 'İZELMAN');
    expect(shouldRecordRecentParking(municipal)).toBe(true);
    expect(toRecordRecentParkingRequest(municipal)).toEqual({
      targetKind: 'MUNICIPAL_FACILITY',
      targetId: 'fac-1',
    });
    expect(shouldRecordRecentParking(null)).toBe(false);
    expect(shouldRecordRecentParking({ kind: 'MUNICIPAL_FACILITY', targetId: '  ' })).toBe(
      false,
    );
  });
});
