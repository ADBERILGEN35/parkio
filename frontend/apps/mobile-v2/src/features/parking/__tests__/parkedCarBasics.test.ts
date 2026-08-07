import {
  isUsableParkedCarCoordinate,
  municipalParkTarget,
  shouldRecordRecentParking,
  toParkedCarView,
} from '@parkio/validation';
import type { ParkingSessionResponse } from '@parkio/types';

describe('parkedCar basics (SPA-11)', () => {
  const session: ParkingSessionResponse = {
    id: '11111111-1111-4111-8111-111111111111',
    status: 'ACTIVE',
    parkingSource: 'MANUAL',
    startedAt: '2026-08-07T08:00:00.000Z',
    endedAt: null,
    latitude: 38.42,
    longitude: 27.14,
    estimatedFee: null,
    lastConfirmedAt: '2026-08-07T08:00:00.000Z',
    completionType: null,
  };

  it('builds parked car view for ACTIVE session', () => {
    const view = toParkedCarView(session);
    expect(view?.sessionId).toBe(session.id);
    expect(view?.returnAvailable).toBe(true);
    expect(isUsableParkedCarCoordinate(view!.latitude, view!.longitude)).toBe(true);
  });

  it('only records RecentParking for municipal targets', () => {
    expect(shouldRecordRecentParking(municipalParkTarget('f1'))).toBe(true);
    expect(shouldRecordRecentParking(null)).toBe(false);
  });
});
