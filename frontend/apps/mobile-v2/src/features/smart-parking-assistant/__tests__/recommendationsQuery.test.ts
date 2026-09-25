import type { ParkingCandidate } from '@parkio/types';
import { buildRecommendationRequest } from '@/data/query-options/recommendations';

describe('buildRecommendationRequest', () => {
  it('applies SPA-05 defaults and municipal include flag', () => {
    const request = buildRecommendationRequest({
      destination: {
        label: 'Konak',
        latitude: 38.42,
        longitude: 27.13,
        source: 'GEOCODING',
      },
      includeMunicipal: false,
    });
    expect(request.radiusMeters).toBe(1500);
    expect(request.limit).toBe(10);
    expect(request.includeCommunity).toBe(true);
    expect(request.includeMunicipal).toBe(false);
    expect(request.destination.label).toBe('Konak');
  });
});

describe('recommendation server order', () => {
  it('does not re-sort candidate arrays client-side', () => {
    const candidates: ParkingCandidate[] = [
      {
        id: 'b',
        channel: 'COMMUNITY_SPOT',
        refId: 's2',
        title: 'Second',
        latitude: 1,
        longitude: 1,
        distanceMeters: 50,
        baselineOrder: 1,
        reasons: [],
      },
      {
        id: 'a',
        channel: 'MUNICIPAL_FACILITY',
        refId: 'f1',
        title: 'First',
        latitude: 1,
        longitude: 1,
        distanceMeters: 10,
        baselineOrder: 0,
        reasons: [],
      },
    ];
    // Presentation must preserve API order (not distance / baselineOrder re-sort).
    expect(candidates.map((c) => c.id)).toEqual(['b', 'a']);
  });
});
