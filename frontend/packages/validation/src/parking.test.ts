import { describe, expect, it } from 'vitest';
import { createSpotFormSchema, nearbySearchSchema, verifySpotSchema } from './parking';

describe('nearbySearchSchema', () => {
  const valid = { lat: '41.0', lng: '29.0', radius: '', limit: '' };

  it('accepts valid coordinates with optional radius/limit left empty', () => {
    const result = nearbySearchSchema.safeParse(valid);
    expect(result.success).toBe(true);
    if (result.success) {
      expect(result.data).toEqual({ lat: 41, lng: 29, radius: undefined, limit: undefined });
    }
  });

  it.each([
    ['lat', '-90', true],
    ['lat', '90', true],
    ['lat', '-90.1', false],
    ['lat', '90.1', false],
    ['lng', '-180', true],
    ['lng', '180', true],
    ['lng', '-180.1', false],
    ['lng', '180.1', false],
  ])('boundary %s=%s -> valid=%s', (field, value, ok) => {
    expect(nearbySearchSchema.safeParse({ ...valid, [field]: value }).success).toBe(ok);
  });

  it('requires lat and lng', () => {
    expect(nearbySearchSchema.safeParse({ ...valid, lat: '' }).success).toBe(false);
    expect(nearbySearchSchema.safeParse({ ...valid, lng: '' }).success).toBe(false);
  });

  it('caps radius at 50000 m', () => {
    expect(nearbySearchSchema.safeParse({ ...valid, radius: '50000' }).success).toBe(true);
    expect(nearbySearchSchema.safeParse({ ...valid, radius: '50001' }).success).toBe(false);
    expect(nearbySearchSchema.safeParse({ ...valid, radius: '0' }).success).toBe(false);
  });

  it('bounds limit to 1..50 whole numbers', () => {
    expect(nearbySearchSchema.safeParse({ ...valid, limit: '1' }).success).toBe(true);
    expect(nearbySearchSchema.safeParse({ ...valid, limit: '50' }).success).toBe(true);
    expect(nearbySearchSchema.safeParse({ ...valid, limit: '0' }).success).toBe(false);
    expect(nearbySearchSchema.safeParse({ ...valid, limit: '51' }).success).toBe(false);
    expect(nearbySearchSchema.safeParse({ ...valid, limit: '2.5' }).success).toBe(false);
  });
});

describe('createSpotFormSchema', () => {
  const valid = {
    latitude: '41.0',
    longitude: '29.0',
    addressText: '',
    description: '',
    manualLocationEdited: false,
    suitableVehicleTypes: ['SEDAN'],
    parkingContext: 'STREET_PARKING',
    legalStatus: 'LEGAL',
    violationReasons: [],
  };

  it('accepts a minimal valid spot', () => {
    expect(createSpotFormSchema.safeParse(valid).success).toBe(true);
  });

  it('requires at least one suitable vehicle type', () => {
    expect(createSpotFormSchema.safeParse({ ...valid, suitableVehicleTypes: [] }).success).toBe(
      false,
    );
  });

  it('requires a violation reason when legalStatus is ILLEGAL_OR_RISKY', () => {
    expect(
      createSpotFormSchema.safeParse({ ...valid, legalStatus: 'ILLEGAL_OR_RISKY' }).success,
    ).toBe(false);
    expect(
      createSpotFormSchema.safeParse({
        ...valid,
        legalStatus: 'ILLEGAL_OR_RISKY',
        violationReasons: ['NO_PARKING_SIGN'],
      }).success,
    ).toBe(true);
  });

  it('does not require violation reasons for legal spots', () => {
    expect(createSpotFormSchema.safeParse({ ...valid, violationReasons: [] }).success).toBe(true);
  });
});

describe('verifySpotSchema', () => {
  it('requires a result', () => {
    expect(verifySpotSchema.safeParse({ result: '' }).success).toBe(false);
  });

  it('accepts a known verification result', () => {
    expect(verifySpotSchema.safeParse({ result: 'AVAILABLE' }).success).toBe(true);
  });

  it('rejects unknown results', () => {
    expect(verifySpotSchema.safeParse({ result: 'MAYBE' }).success).toBe(false);
  });
});
