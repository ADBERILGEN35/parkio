import { PARKING_STATUSES } from '@parkio/types';
import { generateBenchSpots } from '../benchSpots';

const center = { lat: 38.4237, lng: 27.1428 };

describe('generateBenchSpots', () => {
  it('generates exactly the requested count', () => {
    expect(generateBenchSpots({ center, count: 0 })).toHaveLength(0);
    expect(generateBenchSpots({ center, count: 500 })).toHaveLength(500);
  });

  it('is deterministic for a given seed', () => {
    const a = generateBenchSpots({ center, count: 50, seed: 42 });
    const b = generateBenchSpots({ center, count: 50, seed: 42 });
    expect(a).toEqual(b);
  });

  it('varies with the seed', () => {
    const a = generateBenchSpots({ center, count: 50, seed: 1 });
    const b = generateBenchSpots({ center, count: 50, seed: 2 });
    expect(a[10].latitude).not.toBe(b[10].latitude);
  });

  it('clearly labels every spot as synthetic and keeps it inside the spread box', () => {
    const spreadDeg = 0.03;
    const spots = generateBenchSpots({ center, count: 200, spreadDeg });
    for (const spot of spots) {
      expect(spot.id).toMatch(/^bench-\d+$/);
      expect(Math.abs(spot.latitude - center.lat)).toBeLessThanOrEqual(spreadDeg + 1e-9);
      expect(Math.abs(spot.longitude - center.lng)).toBeLessThanOrEqual(spreadDeg + 1e-9);
      expect(PARKING_STATUSES).toContain(spot.status);
    }
  });
});
