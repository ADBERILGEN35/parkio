import { z } from 'zod';
import {
  LEGAL_STATUSES,
  PARKING_CONTEXTS,
  PARKING_SESSION_COMPLETION_TYPES,
  PARKING_SESSION_STATUSES,
  PARKING_SOURCES,
  PARKING_STATUSES,
  SPOT_VEHICLE_TYPES,
  VERIFICATION_RESULTS,
  VIOLATION_REASONS,
  type CommunityClaimResponse,
  type CreateSpotRequest,
  type NearbySearchParams,
  type ParkingSessionHistoryParams,
  type ParkingSessionHistoryResponse,
  type ParkingSessionLifecycleConfig,
  type ParkingSessionResponse,
  type PublicSpotResponse,
  type SpotMediaAccessUrlResponse,
  type SpotResponse,
  type StartParkingSessionRequest,
  type VerifySpotRequest,
} from '@parkio/types';
import {
  finiteNumberSchema,
  instantSchema,
  nonNegativeIntegerSchema,
  uuidSchema,
} from './primitives';

export const PARKING_SESSION_ESTIMATED_FEE_PATTERN =
  /^0*(?:0|[1-9][0-9]{0,9})(?:\.[0-9]{1,2})?$/;
export const PARKING_SESSION_RESPONSE_FEE_PATTERN = /^\d{1,10}\.\d{2}$/;
export const PARKING_SESSION_CURSOR_MAX_LENGTH = 512;

const latitudeSchema = finiteNumberSchema.min(-90).max(90);
const longitudeSchema = finiteNumberSchema.min(-180).max(180);
const parkingSessionCursorSchema = z
  .string()
  .min(1)
  .max(PARKING_SESSION_CURSOR_MAX_LENGTH)
  .regex(/^[A-Za-z0-9_-]+$/);

export const nearbySearchParamsContractSchema = z
  .object({
    lat: latitudeSchema,
    lng: longitudeSchema,
    radius: finiteNumberSchema.positive().max(50_000).optional(),
    limit: z.number().int().min(1).max(50).optional(),
  })
  .strict() satisfies z.ZodType<NearbySearchParams>;

export const createSpotRequestSchema = z
  .object({
    mediaId: uuidSchema,
    latitude: latitudeSchema,
    longitude: longitudeSchema,
    addressText: z.string().max(512).nullable().optional(),
    description: z.string().max(1_000).nullable().optional(),
    manualLocationEdited: z.boolean().optional(),
    suitableVehicleTypes: z.array(z.enum(SPOT_VEHICLE_TYPES)).min(1),
    parkingContext: z.enum(PARKING_CONTEXTS),
    legalStatus: z.enum(LEGAL_STATUSES),
    violationReasons: z.array(z.enum(VIOLATION_REASONS)).nullable().optional(),
  })
  .strict() satisfies z.ZodType<CreateSpotRequest>;

export const verifySpotRequestSchema = z
  .object({ result: z.enum(VERIFICATION_RESULTS) })
  .strict() satisfies z.ZodType<VerifySpotRequest>;

export const startParkingSessionRequestSchema = z
  .object({
    latitude: latitudeSchema,
    longitude: longitudeSchema,
    estimatedFee: z
      .string()
      .max(32)
      .regex(PARKING_SESSION_ESTIMATED_FEE_PATTERN)
      .nullable()
      .optional(),
  })
  .strict() satisfies z.ZodType<StartParkingSessionRequest>;

export const parkingSessionHistoryParamsSchema = z
  .object({
    size: z.number().int().min(1).max(100).optional(),
    cursor: parkingSessionCursorSchema.optional(),
  })
  .strict() satisfies z.ZodType<ParkingSessionHistoryParams>;

export const publicSpotResponseSchema = z
  .object({
    id: uuidSchema,
    mediaId: uuidSchema,
    latitude: latitudeSchema,
    longitude: longitudeSchema,
    addressText: z.string().nullable(),
    description: z.string().nullable(),
    manualLocationEdited: z.boolean(),
    suitableVehicleTypes: z.array(z.enum(SPOT_VEHICLE_TYPES)),
    parkingContext: z.enum(PARKING_CONTEXTS),
    legalStatus: z.enum(LEGAL_STATUSES),
    violationReasons: z.array(z.enum(VIOLATION_REASONS)),
    status: z.enum(PARKING_STATUSES),
    expiresAt: instantSchema.nullable(),
    createdAt: instantSchema,
    updatedAt: instantSchema,
  })
  .strip() satisfies z.ZodType<PublicSpotResponse>;

export const spotResponseSchema = publicSpotResponseSchema
  .extend({
    ownerUserId: uuidSchema,
    confidenceScore: finiteNumberSchema,
    verificationCount: nonNegativeIntegerSchema,
    filledReportCount: nonNegativeIntegerSchema,
  })
  .strip() satisfies z.ZodType<SpotResponse>;

export const spotMediaAccessUrlResponseSchema = z
  .object({
    spotId: uuidSchema,
    mediaId: uuidSchema,
    accessUrl: z.string(),
    expiresAt: instantSchema,
  })
  .strip() satisfies z.ZodType<SpotMediaAccessUrlResponse>;

export const parkingSessionResponseSchema = z
  .object({
    id: uuidSchema,
    status: z.enum(PARKING_SESSION_STATUSES),
    parkingSource: z.enum(PARKING_SOURCES),
    startedAt: instantSchema,
    endedAt: instantSchema.nullable(),
    latitude: latitudeSchema,
    longitude: longitudeSchema,
    estimatedFee: z.string().regex(PARKING_SESSION_RESPONSE_FEE_PATTERN).nullable(),
    lastConfirmedAt: instantSchema.nullable(),
    completionType: z.enum(PARKING_SESSION_COMPLETION_TYPES).nullable(),
  })
  .strip() satisfies z.ZodType<ParkingSessionResponse>;

export const parkingSessionHistoryResponseSchema = z
  .object({
    items: z.array(parkingSessionResponseSchema),
    nextCursor: parkingSessionCursorSchema.nullable(),
  })
  .strip() satisfies z.ZodType<ParkingSessionHistoryResponse>;

export const parkingSessionLifecycleConfigSchema = z
  .object({
    confirmAfterMs: z.number().int().positive(),
    reminder2AfterMs: z.number().int().positive(),
    autoCompleteAfterMs: z.number().int().positive(),
    confirmAfter: z.string().min(1),
    reminder2After: z.string().min(1),
    autoCompleteAfter: z.string().min(1),
    remindersEnabled: z.boolean(),
    autoCompleteEnabled: z.boolean(),
  })
  .strip() satisfies z.ZodType<ParkingSessionLifecycleConfig>;

export const communityClaimResponseSchema =
  publicSpotResponseSchema satisfies z.ZodType<CommunityClaimResponse>;

export const publicSpotListResponseSchema = z.array(publicSpotResponseSchema);
export const spotListResponseSchema = z.array(spotResponseSchema);
