import { z } from 'zod';
import { VEHICLE_TYPES } from '@parkio/types';

// Mirrors user-service UserPreference.MIN_RADIUS_METERS / MAX_RADIUS_METERS.
export const PREFERRED_RADIUS_MIN_METERS = 100;
export const PREFERRED_RADIUS_MAX_METERS = 50_000;

/**
 * Profile edit form. Empty strings mean "not provided" — the app omits them
 * from the PATCH body (backend treats null/absent as "leave unchanged").
 */
export const profileUpdateSchema = z.object({
  displayName: z
    .string()
    .trim()
    .max(50, 'Display name must be at most 50 characters')
    .refine((v) => v === '' || v.length >= 2, 'Display name must be at least 2 characters'),
  phoneNumber: z.string().trim().max(32, 'Phone number must be at most 32 characters'),
  city: z.string().trim().max(100, 'City must be at most 100 characters'),
});

export const preferencesUpdateSchema = z.object({
  preferredRadiusMeters: z.coerce
    .number()
    .int('Radius must be a whole number')
    .min(PREFERRED_RADIUS_MIN_METERS, `Radius must be at least ${PREFERRED_RADIUS_MIN_METERS} m`)
    .max(PREFERRED_RADIUS_MAX_METERS, `Radius must be at most ${PREFERRED_RADIUS_MAX_METERS} m`),
  notificationsEnabled: z.boolean(),
});

/** Vehicle upsert form. Empty vehicle type / plate clear the stored value (PUT replaces). */
export const vehicleUpsertSchema = z.object({
  vehicleType: z.enum(VEHICLE_TYPES).or(z.literal('')),
  plate: z.string().trim().max(16, 'Plate must be at most 16 characters'),
});

export type ProfileUpdateFormValues = z.infer<typeof profileUpdateSchema>;
export type PreferencesUpdateFormValues = z.infer<typeof preferencesUpdateSchema>;
export type VehicleUpsertFormValues = z.infer<typeof vehicleUpsertSchema>;
