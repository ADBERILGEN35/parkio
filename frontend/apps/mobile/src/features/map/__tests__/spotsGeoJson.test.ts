import { spotsToGeoJson } from '../webmap/spotsGeoJson';
import { makeSpot } from './fixtures';

describe('spotsToGeoJson', () => {
  it('maps spots to GeoJSON point features with [lng, lat] order', () => {
    const fc = spotsToGeoJson([makeSpot({ id: 'a', latitude: 38.4, longitude: 27.1 })], null);

    expect(fc.type).toBe('FeatureCollection');
    expect(fc.features).toHaveLength(1);
    expect(fc.features[0].geometry.coordinates).toEqual([27.1, 38.4]);
    expect(fc.features[0].properties.id).toBe('a');
  });

  it('derives tone from the spot status (no fabricated value)', () => {
    const fc = spotsToGeoJson(
      [
        makeSpot({ id: 'ok', status: 'VERIFIED' }),
        makeSpot({ id: 'warn', status: 'SUSPICIOUS' }),
        makeSpot({ id: 'full', status: 'FILLED' }),
        makeSpot({ id: 'gone', status: 'EXPIRED' }),
      ],
      null,
    );

    const toneById = Object.fromEntries(fc.features.map((f) => [f.properties.id, f.properties.tone]));
    expect(toneById).toEqual({ ok: 'success', warn: 'warning', full: 'danger', gone: 'muted' });
  });

  it('flags only the selected spot', () => {
    const fc = spotsToGeoJson([makeSpot({ id: 'a' }), makeSpot({ id: 'b' })], 'b');
    const selected = fc.features.filter((f) => f.properties.selected).map((f) => f.properties.id);
    expect(selected).toEqual(['b']);
  });

  it('returns an empty collection for no spots', () => {
    expect(spotsToGeoJson([], null).features).toEqual([]);
  });
});
