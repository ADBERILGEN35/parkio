import { describe, expect, it } from 'vitest';
import { preferencesUpdateSchema, profileUpdateSchema, vehicleUpsertSchema } from './user';

describe('profileUpdateSchema', () => {
  const valid = { displayName: 'Ada', phoneNumber: '', city: '' };

  it('accepts a valid profile', () => {
    expect(profileUpdateSchema.safeParse(valid).success).toBe(true);
  });

  it('allows an empty displayName (means "not provided")', () => {
    expect(profileUpdateSchema.safeParse({ ...valid, displayName: '' }).success).toBe(true);
  });

  it('rejects a 1-character displayName', () => {
    expect(profileUpdateSchema.safeParse({ ...valid, displayName: 'A' }).success).toBe(false);
  });

  it('rejects a displayName over 50 characters', () => {
    expect(
      profileUpdateSchema.safeParse({ ...valid, displayName: 'a'.repeat(51) }).success,
    ).toBe(false);
  });
});

describe('preferencesUpdateSchema', () => {
  it.each([
    ['100', true],
    ['99', false],
    ['50000', true],
    ['50001', false],
    ['1000.5', false],
  ])('preferredRadiusMeters=%s -> valid=%s', (radius, ok) => {
    expect(
      preferencesUpdateSchema.safeParse({
        preferredRadiusMeters: radius,
        notificationsEnabled: true,
      }).success,
    ).toBe(ok);
  });
});

describe('vehicleUpsertSchema', () => {
  it('accepts a known vehicle type', () => {
    expect(vehicleUpsertSchema.safeParse({ vehicleType: 'SEDAN', plate: '34AB123' }).success).toBe(
      true,
    );
  });

  it('accepts empty vehicle type/plate (clears the stored value)', () => {
    expect(vehicleUpsertSchema.safeParse({ vehicleType: '', plate: '' }).success).toBe(true);
  });

  it('rejects unknown vehicle types', () => {
    expect(vehicleUpsertSchema.safeParse({ vehicleType: 'SPACESHIP', plate: '' }).success).toBe(
      false,
    );
  });

  it('rejects plates over 16 characters', () => {
    expect(
      vehicleUpsertSchema.safeParse({ vehicleType: 'SEDAN', plate: 'P'.repeat(17) }).success,
    ).toBe(false);
  });
});
