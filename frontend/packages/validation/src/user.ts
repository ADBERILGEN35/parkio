import { z } from 'zod';
import { VEHICLE_TYPES } from '@parkio/types';

// Mirrors user-service UserPreference.MIN_RADIUS_METERS / MAX_RADIUS_METERS.
export const PREFERRED_RADIUS_MIN_METERS = 100;
export const PREFERRED_RADIUS_MAX_METERS = 50_000;
export const SMART_RETURN_LEAD_MINUTES_MIN = 5;
export const SMART_RETURN_LEAD_MINUTES_MAX = 120;

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

export const smartReturnSettingsSchema = z
  .object({
    enabled: z.boolean(),
    homeLatitude: z.coerce
      .number()
      .min(-90, 'Latitude must be between -90 and 90')
      .max(90, 'Latitude must be between -90 and 90')
      .nullable(),
    homeLongitude: z.coerce
      .number()
      .min(-180, 'Longitude must be between -180 and 180')
      .max(180, 'Longitude must be between -180 and 180')
      .nullable(),
    homeLabel: z.string().trim().max(160, 'Home label must be at most 160 characters'),
    defaultReturnTime: z.string().regex(/^([01]\d|2[0-3]):[0-5]\d$/, 'Use HH:mm time'),
    reminderLeadMinutes: z.coerce
      .number()
      .int('Lead time must be a whole number')
      .min(SMART_RETURN_LEAD_MINUTES_MIN, `Lead time must be at least ${SMART_RETURN_LEAD_MINUTES_MIN} minutes`)
      .max(SMART_RETURN_LEAD_MINUTES_MAX, `Lead time must be at most ${SMART_RETURN_LEAD_MINUTES_MAX} minutes`),
  })
  .refine((v) => !v.enabled || (v.homeLatitude !== null && v.homeLongitude !== null), {
    message: 'Choose a saved home area before enabling Smart Return',
    path: ['homeLatitude'],
  });

export const smartReturnTodaySchema = z.object({
  returnTime: z.string().regex(/^([01]\d|2[0-3]):[0-5]\d$/, 'Use HH:mm time'),
});

export type ProfileUpdateFormValues = z.infer<typeof profileUpdateSchema>;
export type PreferencesUpdateFormValues = z.infer<typeof preferencesUpdateSchema>;
export type VehicleUpsertFormValues = z.infer<typeof vehicleUpsertSchema>;
export type SmartReturnSettingsFormValues = z.infer<typeof smartReturnSettingsSchema>;
export type SmartReturnTodayFormValues = z.infer<typeof smartReturnTodaySchema>;
