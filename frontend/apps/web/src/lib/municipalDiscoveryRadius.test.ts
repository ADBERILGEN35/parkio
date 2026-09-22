import { describe, expect, it } from 'vitest';
import {
  DEFAULT_MUNICIPAL_RADIUS_METERS,
  MAX_MUNICIPAL_RADIUS_METERS,
  clampMunicipalRadiusMeters,
  formatMunicipalRadiusLabel,
  isValidMunicipalRadiusMeters,
  nextMunicipalRadiusMeters,
  previousMunicipalRadiusMeters,
} from './municipalDiscoveryRadius';

describe('municipalDiscoveryRadius', () => {
  it('defaults and clamps to API bounds', () => {
    expect(DEFAULT_MUNICIPAL_RADIUS_METERS).toBe(5000);
    expect(MAX_MUNICIPAL_RADIUS_METERS).toBe(50_000);
    expect(clampMunicipalRadiusMeters(Number.NaN)).toBe(DEFAULT_MUNICIPAL_RADIUS_METERS);
    expect(clampMunicipalRadiusMeters(0)).toBe(1);
    expect(clampMunicipalRadiusMeters(50_001)).toBe(50_000);
    expect(clampMunicipalRadiusMeters(2500.9)).toBe(2500);
  });

  it('validates explicit integer radii only', () => {
    expect(isValidMunicipalRadiusMeters(5000)).toBe(true);
    expect(isValidMunicipalRadiusMeters(1)).toBe(true);
    expect(isValidMunicipalRadiusMeters(50_000)).toBe(true);
    expect(isValidMunicipalRadiusMeters(0)).toBe(false);
    expect(isValidMunicipalRadiusMeters(50_001)).toBe(false);
    expect(isValidMunicipalRadiusMeters(1000.5)).toBe(false);
    expect(isValidMunicipalRadiusMeters(undefined)).toBe(false);
  });

  it('offers expansion only while a larger preset exists', () => {
    expect(nextMunicipalRadiusMeters(1000)).toBe(2000);
    expect(nextMunicipalRadiusMeters(5000)).toBe(10_000);
    expect(nextMunicipalRadiusMeters(25_000)).toBe(50_000);
    expect(nextMunicipalRadiusMeters(50_000)).toBeNull();
    expect(nextMunicipalRadiusMeters(49_999)).toBe(50_000);
  });

  it('offers reduction for capped searches while a smaller preset exists', () => {
    expect(previousMunicipalRadiusMeters(5000)).toBe(3000);
    expect(previousMunicipalRadiusMeters(1000)).toBeNull();
    expect(previousMunicipalRadiusMeters(2000)).toBe(1000);
    expect(previousMunicipalRadiusMeters(50_000)).toBe(25_000);
  });

  it('formats labels for UI', () => {
    expect(formatMunicipalRadiusLabel(5000)).toBe('5 km');
    expect(formatMunicipalRadiusLabel(1000)).toBe('1 km');
    expect(formatMunicipalRadiusLabel(2500)).toBe('2500 m');
  });
});
