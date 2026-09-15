import {
  buildFitDiscoveryFrame,
  discoveryFrameRevision,
} from '../fitDiscoveryFrame';

describe('buildFitDiscoveryFrame', () => {
  it('returns null when there are no municipal points', () => {
    expect(buildFitDiscoveryFrame({ lat: 38.42, lng: 27.14 }, [])).toBeNull();
  });

  it('includes anchor and all municipal points in the bounds', () => {
    const frame = buildFitDiscoveryFrame({ lat: 38.42, lng: 27.14 }, [
      { lat: 38.45, lng: 27.1 },
      { lat: 38.4, lng: 27.2 },
    ]);
    expect(frame).toEqual(
      expect.objectContaining({
        west: 27.1,
        east: 27.2,
        south: 38.4,
        north: 38.45,
        maxZoom: 15,
      }),
    );
  });

  it('builds a stable revision from origin + ids', () => {
    expect(discoveryFrameRevision({ lat: 38.4237, lng: 27.1428 }, ['a', 'b'])).toBe(
      '38.423700,27.142800:a,b',
    );
  });
});
