import { describe, expect, it } from 'vitest';
import { buildRasterStyle, expandSubdomains } from './rasterStyle';

describe('expandSubdomains', () => {
  it('expands {s} into a/b/c', () => {
    expect(expandSubdomains('https://{s}.tile.osm.org/{z}/{x}/{y}.png')).toEqual([
      'https://a.tile.osm.org/{z}/{x}/{y}.png',
      'https://b.tile.osm.org/{z}/{x}/{y}.png',
      'https://c.tile.osm.org/{z}/{x}/{y}.png',
    ]);
  });

  it('returns a single url without {s}', () => {
    expect(expandSubdomains('https://tiles.example/{z}/{x}/{y}.png')).toEqual([
      'https://tiles.example/{z}/{x}/{y}.png',
    ]);
  });
});

describe('buildRasterStyle', () => {
  it('builds a valid key-free OSM style by default', () => {
    const style = buildRasterStyle();
    expect(style.version).toBe(8);
    expect(style.sources['osm-raster'].tiles).toHaveLength(3);
    expect(style.layers[0]).toEqual({ id: 'osm-raster', type: 'raster', source: 'osm-raster' });
    expect(style.sources['osm-raster'].attribution).toContain('OpenStreetMap');
  });

  it('honors overrides', () => {
    const style = buildRasterStyle({
      tileUrl: 'https://tiles.example/{z}/{x}/{y}.png',
      attribution: 'Custom',
      maxzoom: 17,
    });
    expect(style.sources['osm-raster'].tiles).toEqual(['https://tiles.example/{z}/{x}/{y}.png']);
    expect(style.sources['osm-raster'].attribution).toBe('Custom');
    expect(style.sources['osm-raster'].maxzoom).toBe(17);
  });
});
