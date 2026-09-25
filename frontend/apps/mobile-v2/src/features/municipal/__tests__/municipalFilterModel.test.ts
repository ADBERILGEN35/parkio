import {
  DEFAULT_MUNICIPAL_MAP_FILTERS,
  MUNICIPAL_FILTER_SCHEMA_VERSION,
  MUNICIPAL_RADIUS_OPTIONS_M,
  countActiveMunicipalFilters,
  formatMunicipalRadiusLabel,
  hasActiveMunicipalMapFilters,
  resetMunicipalMapFilters,
  sanitizeMunicipalMapFilters,
} from '../municipalFilterModel';

describe('municipalFilterModel', () => {
  it('defaults layer on, all sources, all occupancy, 1500 m radius', () => {
    expect(DEFAULT_MUNICIPAL_MAP_FILTERS).toEqual({
      version: MUNICIPAL_FILTER_SCHEMA_VERSION,
      layerEnabled: true,
      source: 'all',
      occupancy: 'all',
      radiusMeters: 1500,
    });
  });

  it('exposes product-approved radius choices including the default', () => {
    expect(MUNICIPAL_RADIUS_OPTIONS_M).toEqual([500, 1000, 1500, 3000, 5000]);
    expect(MUNICIPAL_RADIUS_OPTIONS_M).toContain(DEFAULT_MUNICIPAL_MAP_FILTERS.radiusMeters);
  });

  it('counts only source, occupancy, and radius deviations', () => {
    expect(countActiveMunicipalFilters(DEFAULT_MUNICIPAL_MAP_FILTERS)).toBe(0);
    expect(
      countActiveMunicipalFilters({
        ...DEFAULT_MUNICIPAL_MAP_FILTERS,
        layerEnabled: false,
      }),
    ).toBe(0);
    expect(
      countActiveMunicipalFilters({
        ...DEFAULT_MUNICIPAL_MAP_FILTERS,
        source: 'izum',
        occupancy: 'live',
        radiusMeters: 500,
      }),
    ).toBe(3);
  });

  it('sanitizes invalid persisted values to safe defaults', () => {
    expect(sanitizeMunicipalMapFilters(null)).toEqual(DEFAULT_MUNICIPAL_MAP_FILTERS);
    expect(
      sanitizeMunicipalMapFilters({
        version: 1,
        layerEnabled: 'yes',
        source: 'IZUM',
        occupancy: 'available',
        radiusMeters: 999,
      }),
    ).toEqual(DEFAULT_MUNICIPAL_MAP_FILTERS);
    expect(
      sanitizeMunicipalMapFilters({
        version: 1,
        layerEnabled: false,
        source: 'osm',
        occupancy: 'static',
        radiusMeters: 3000,
      }),
    ).toEqual({
      version: 1,
      layerEnabled: false,
      source: 'osm',
      occupancy: 'static',
      radiusMeters: 3000,
    });
  });

  it('resets filters without disabling the layer', () => {
    expect(
      resetMunicipalMapFilters({
        ...DEFAULT_MUNICIPAL_MAP_FILTERS,
        layerEnabled: true,
        source: 'izum',
        occupancy: 'live',
        radiusMeters: 5000,
      }),
    ).toEqual(DEFAULT_MUNICIPAL_MAP_FILTERS);

    expect(
      resetMunicipalMapFilters({
        ...DEFAULT_MUNICIPAL_MAP_FILTERS,
        layerEnabled: false,
        source: 'osm',
      }).layerEnabled,
    ).toBe(false);
  });

  it('formats radius labels naturally', () => {
    expect(formatMunicipalRadiusLabel(500)).toBe('500 m');
    expect(formatMunicipalRadiusLabel(1000)).toBe('1 km');
    expect(formatMunicipalRadiusLabel(1500)).toBe('1.5 km');
    expect(hasActiveMunicipalMapFilters(DEFAULT_MUNICIPAL_MAP_FILTERS)).toBe(false);
  });
});
