import type { PublicExploreFacility } from '@parkio/types';
import { toRenderablePublicFacilities } from '../renderablePublicFacilities';

function facility(overrides: Partial<PublicExploreFacility> = {}): PublicExploreFacility {
  return {
    id: 'fac-1',
    displayName: 'Konak',
    operatorName: 'İZUM',
    facilityType: 'OFF_STREET',
    addressText: 'İzmir',
    latitude: 38.42,
    longitude: 27.14,
    capacityTotal: 100,
    availableSpaces: 12,
    availabilityFreshness: 'LIVE',
    dataUpdatedAt: '2026-09-01T12:00:00Z',
    sourceLabel: 'Izmir Buyuksehir Belediyesi / IZUM',
    attribution: 'İZUM',
    ...overrides,
  };
}

describe('toRenderablePublicFacilities', () => {
  it('keeps valid facilities and drops invalid coordinates', () => {
    const renderable = toRenderablePublicFacilities([
      facility({ id: 'a' }),
      facility({ id: 'b', latitude: Number.NaN }),
      facility({ id: 'c', longitude: Number.POSITIVE_INFINITY }),
      facility({ id: 'd', latitude: 38.5, longitude: 27.2 }),
    ]);
    expect(renderable.map((f) => f.id)).toEqual(['a', 'd']);
  });

  it('dedupes by id (first wins)', () => {
    const renderable = toRenderablePublicFacilities([
      facility({ id: 'same', displayName: 'First' }),
      facility({ id: 'same', displayName: 'Second' }),
    ]);
    expect(renderable).toHaveLength(1);
    expect(renderable[0]?.displayName).toBe('First');
  });

  it('DISPLAYED_VISIBLE_COUNT equals renderable length', () => {
    const facilities = [facility({ id: '1' }), facility({ id: '2', latitude: NaN })];
    const renderable = toRenderablePublicFacilities(facilities);
    expect(renderable.length).toBe(1);
  });
});
