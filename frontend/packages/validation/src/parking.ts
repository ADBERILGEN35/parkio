import { z } from 'zod';
import {
  LEGAL_STATUSES,
  PARKING_CONTEXTS,
  SPOT_VEHICLE_TYPES,
  VERIFICATION_RESULTS,
  VIOLATION_REASONS,
} from '@parkio/types';

// Mirrors parking-service search settings (max-radius-meters / max-result-limit).
export const NEARBY_RADIUS_MAX_METERS = 50_000;
export const NEARBY_LIMIT_MAX = 50;

function emptyToUndefined(value: unknown) {
  return typeof value === 'string' && value.trim() === '' ? undefined : value;
}

/**
 * Nearby search form. Radius/limit are optional — the backend applies its own
 * defaults (1000 m / 10 results) when they are absent.
 */
export const nearbySearchSchema = z.object({
  lat: z.preprocess(
    emptyToUndefined,
    z.coerce
      .number({ invalid_type_error: 'Latitude is required' })
      .min(-90, 'Latitude must be between -90 and 90')
      .max(90, 'Latitude must be between -90 and 90'),
  ),
  lng: z.preprocess(
    emptyToUndefined,
    z.coerce
      .number({ invalid_type_error: 'Longitude is required' })
      .min(-180, 'Longitude must be between -180 and 180')
      .max(180, 'Longitude must be between -180 and 180'),
  ),
  radius: z.preprocess(
    emptyToUndefined,
    z.coerce
      .number({ invalid_type_error: 'Radius must be a number' })
      .positive('Radius must be greater than 0')
      .max(NEARBY_RADIUS_MAX_METERS, `Radius must be at most ${NEARBY_RADIUS_MAX_METERS} m`)
      .optional(),
  ),
  limit: z.preprocess(
    emptyToUndefined,
    z.coerce
      .number({ invalid_type_error: 'Limit must be a number' })
      .int('Limit must be a whole number')
      .min(1, 'Limit must be at least 1')
      .max(NEARBY_LIMIT_MAX, `Limit must be at most ${NEARBY_LIMIT_MAX}`)
      .optional(),
  ),
});

export type NearbySearchFormValues = z.infer<typeof nearbySearchSchema>;

const createSpotFields = z.object({
  latitude: z.preprocess(
    emptyToUndefined,
    z.coerce
      .number({ invalid_type_error: 'Latitude is required' })
      .min(-90, 'Latitude must be between -90 and 90')
      .max(90, 'Latitude must be between -90 and 90'),
  ),
  longitude: z.preprocess(
    emptyToUndefined,
    z.coerce
      .number({ invalid_type_error: 'Longitude is required' })
      .min(-180, 'Longitude must be between -180 and 180')
      .max(180, 'Longitude must be between -180 and 180'),
  ),
  addressText: z.string().trim().max(512, 'Address must be at most 512 characters'),
  description: z.string().trim().max(1000, 'Description must be at most 1000 characters'),
  manualLocationEdited: z.boolean(),
  suitableVehicleTypes: z
    .array(z.enum(SPOT_VEHICLE_TYPES))
    .min(1, 'Select at least one vehicle type'),
  parkingContext: z.preprocess(
    emptyToUndefined,
    z.enum(PARKING_CONTEXTS, {
      required_error: 'Parking context is required',
      invalid_type_error: 'Parking context is required',
    }),
  ),
  legalStatus: z.preprocess(
    emptyToUndefined,
    z.enum(LEGAL_STATUSES, {
      required_error: 'Legal status is required',
      invalid_type_error: 'Legal status is required',
    }),
  ),
  violationReasons: z.array(z.enum(VIOLATION_REASONS)),
});

function requireViolationReasonsWhenIllegal(
  values: { legalStatus?: string; violationReasons: string[] },
  ctx: z.RefinementCtx,
) {
  if (values.legalStatus === 'ILLEGAL_OR_RISKY' && values.violationReasons.length === 0) {
    ctx.addIssue({
      code: z.ZodIssueCode.custom,
      path: ['violationReasons'],
      message: 'Select at least one violation reason for an illegal/risky spot',
    });
  }
}

/** Create-spot form (mediaId comes from the upload step, not the form). */
export const createSpotFormSchema = createSpotFields.superRefine(requireViolationReasonsWhenIllegal);

/** Full request shape — form fields plus the uploaded mediaId. */
export const createParkingSpotSchema = createSpotFields
  .extend({ mediaId: z.string().uuid('mediaId must be a UUID') })
  .superRefine(requireViolationReasonsWhenIllegal);

export type CreateSpotFormValues = z.infer<typeof createSpotFormSchema>;
export type CreateParkingSpotFormValues = z.infer<typeof createParkingSpotSchema>;

/**
 * Verify-spot form — mirrors `VerifySpotRequest`, which only carries the
 * observed result (no note/description or vehicle-mismatch fields exist).
 */
export const verifySpotSchema = z.object({
  result: z.preprocess(
    emptyToUndefined,
    z.enum(VERIFICATION_RESULTS, {
      required_error: 'Select what you observed',
      invalid_type_error: 'Select what you observed',
    }),
  ),
});

export type VerifySpotFormValues = z.infer<typeof verifySpotSchema>;
