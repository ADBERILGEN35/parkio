import { z } from 'zod';
import {
  isValidClaimedRegion,
  type AiValidationResult,
  type ClaimedRegion,
  type MediaAccessUrl,
  type MediaMetadata,
  type UploadMediaResponse,
} from '@parkio/types';
import {
  finiteNumberSchema,
  instantSchema,
  integerSchema,
  nonNegativeIntegerSchema,
  uuidSchema,
} from './primitives';

const mediaStatusSchema = z.enum(['PENDING_SCAN', 'READY', 'REJECTED', 'DELETED']);
const aiValidationStatusSchema = z.enum(['PASSED', 'WARNING', 'FAILED']);
const aiValidationDecisionSchema = z.enum(['ACCEPT', 'REVIEW', 'REJECT']);
const aiRiskTypeSchema = z.enum([
  'NO_PARKING_SIGN',
  'GARAGE_ENTRANCE',
  'BUS_STOP',
  'PEDESTRIAN_CROSSING',
  'FIRE_HYDRANT',
  'SIDEWALK',
  'TRAFFIC_FLOW_BLOCKING',
  'PRIVATE_PROPERTY',
  'LOW_IMAGE_QUALITY',
  'NOT_A_PARKING_SPOT',
  'UNKNOWN',
]);
const aiValidationTypeSchema = z.enum([
  'PARKING_SPACE_VISIBILITY',
  'EMPTY_SPACE_DETECTION',
  'VEHICLE_FIT_ESTIMATION',
  'LEGAL_RISK_DETECTION',
  'IMAGE_QUALITY',
  'DUPLICATE_RISK',
]);
const aiVehicleTypeSchema = z.enum(['SEDAN', 'HATCHBACK', 'SUV', 'VAN', 'MOTORCYCLE', 'ANY']);

export const claimedRegionResponseSchema = z
  .object({
    x: finiteNumberSchema.min(0).max(1),
    y: finiteNumberSchema.min(0).max(1),
    width: finiteNumberSchema.positive().max(1),
    height: finiteNumberSchema.positive().max(1),
  })
  .strip()
  .refine(isValidClaimedRegion, { message: 'claimed region must be valid normalized geometry' }) satisfies z.ZodType<ClaimedRegion>;

export const uploadMediaResponseSchema = z
  .object({
    mediaId: uuidSchema,
    status: mediaStatusSchema,
    contentType: z.string(),
    fileSize: nonNegativeIntegerSchema,
    claimedRegion: claimedRegionResponseSchema.nullable(),
  })
  .strip() satisfies z.ZodType<UploadMediaResponse>;

export const mediaAccessUrlResponseSchema = z
  .object({
    mediaId: uuidSchema,
    accessUrl: z.string(),
    expiresAt: instantSchema,
  })
  .strip() satisfies z.ZodType<MediaAccessUrl>;

export const mediaMetadataResponseSchema = z
  .object({
    mediaId: uuidSchema,
    ownerUserId: uuidSchema,
    contentType: z.string(),
    fileSize: nonNegativeIntegerSchema,
    status: mediaStatusSchema,
    claimedRegion: claimedRegionResponseSchema.nullable(),
    createdAt: instantSchema,
    updatedAt: instantSchema,
  })
  .strip() satisfies z.ZodType<MediaMetadata>;

const aiValidationFindingResponseSchema = z
  .object({
    id: uuidSchema,
    validationType: aiValidationTypeSchema,
    riskType: aiRiskTypeSchema.nullable(),
    score: integerSchema.min(0).max(100),
    message: z.string(),
    createdAt: instantSchema,
  })
  .strip();

const aiVehicleFitResponseSchema = z
  .object({
    id: uuidSchema,
    vehicleType: aiVehicleTypeSchema,
    fitScore: integerSchema.min(0).max(100),
    createdAt: instantSchema,
  })
  .strip();

export const aiValidationResultResponseSchema = z
  .object({
    id: uuidSchema,
    mediaId: uuidSchema,
    parkingSpotId: uuidSchema.nullable(),
    requestedByUserId: uuidSchema.nullable(),
    status: aiValidationStatusSchema,
    decision: aiValidationDecisionSchema,
    reasonCode: z.string().nullable(),
    claimedRegionAssessment: z.string().nullable(),
    vehicleFitEstimate: z.string().nullable(),
    obstructionAssessment: z.string().nullable(),
    legalityAccessAssessment: z.string().nullable(),
    emptySpaceConfidence: integerSchema.min(0).max(100),
    legalRiskScore: integerSchema.min(0).max(100),
    imageQualityScore: integerSchema.min(0).max(100),
    aiConfidence: integerSchema.min(0).max(100),
    detectedRiskTypes: z.array(aiRiskTypeSchema),
    findings: z.array(aiValidationFindingResponseSchema),
    vehicleFitEstimates: z.array(aiVehicleFitResponseSchema),
    createdAt: instantSchema,
  })
  .strip() satisfies z.ZodType<AiValidationResult>;
