import { z } from 'zod';
import type {
  CandidateAvailability,
  CandidateScoreBreakdown,
  InventoryStatus,
  ParkingCandidate,
  RankingEvaluationOutcomeRequest,
  RankingEvaluationOutcomeResponse,
  RecommendationDestinationInput,
  RecommendationReason,
  RecommendationRequest,
  RecommendationResponse,
} from '@parkio/types';
import { destinationSchema, destinationSourceSchema, placeIdentitySchema } from './destination';
import { finiteNumberSchema } from './primitives';

const placeIdentityInputSchema = placeIdentitySchema
  .pick({ provider: true, providerPlaceId: true })
  .strip();

export const parkingCandidateChannelSchema = z.enum(['COMMUNITY_SPOT', 'MUNICIPAL_FACILITY']);

export const inventoryChannelStatusSchema = z.enum([
  'AVAILABLE',
  'EMPTY',
  'DEGRADED',
  'DISABLED',
]);

export const recommendationReasonCodeSchema = z.enum([
  'CLOSE_TO_DESTINATION',
  'LIVE_AVAILABILITY',
  'HIGH_CAPACITY',
  'STATIC_INVENTORY',
  'COMMUNITY_FRESH',
  'INVENTORY_DEGRADED',
  'FAVOURITE',
  'HIGH_CONFIDENCE',
]);

export const rankingVersionSchema = z.enum(['DISTANCE_BASELINE_V1', 'DETERMINISTIC_V1']);

export const rankingStatusSchema = z.enum(['DISABLED', 'APPLIED', 'FALLBACK']);

export const recommendationReasonSchema = z
  .object({
    code: recommendationReasonCodeSchema,
    parameters: z.record(z.string(), z.unknown()).nullish(),
    messageKey: z.string().nullish(),
  })
  .strip() satisfies z.ZodType<RecommendationReason>;

const municipalFreshnessSchema = z.enum([
  'LIVE',
  'AGING',
  'STALE',
  'UNAVAILABLE',
  'INVALID',
]);

export const candidateAvailabilitySchema = z
  .object({
    kind: z.enum(['MUNICIPAL', 'COMMUNITY']),
    freshness: municipalFreshnessSchema.nullish(),
    availableSpaces: z.number().int().nullish(),
    occupiedSpaces: z.number().int().nullish(),
    capacityTotal: z.number().int().nullish(),
    sourceLabel: z.string().nullish(),
    observationTimestamp: z.string().nullish(),
    communityStatus: z.string().nullish(),
    expiresAt: z.string().nullish(),
  })
  .strip() satisfies z.ZodType<CandidateAvailability>;

export const candidateScoreBreakdownSchema = z
  .object({
    distance: finiteNumberSchema.min(0).max(1),
    freshness: finiteNumberSchema.min(0).max(1),
    capacity: finiteNumberSchema.min(0).max(1),
    confidence: finiteNumberSchema.min(0).max(1),
    favourite: finiteNumberSchema.min(0).max(1),
  })
  .strip() satisfies z.ZodType<CandidateScoreBreakdown>;

export const parkingCandidateSchema = z
  .object({
    id: z.string().min(1),
    channel: parkingCandidateChannelSchema,
    refId: z.string().uuid(),
    title: z.string().min(1),
    latitude: finiteNumberSchema.min(-90).max(90),
    longitude: finiteNumberSchema.min(-180).max(180),
    distanceMeters: z.number().int().nonnegative(),
    availability: candidateAvailabilitySchema.nullish(),
    sourceLabel: z.string().nullish(),
    baselineOrder: z.number().int().nonnegative(),
    reasons: z.array(recommendationReasonSchema),
    score: finiteNumberSchema.min(0).max(1).nullish(),
    scoreBreakdown: candidateScoreBreakdownSchema.nullish(),
    rankingVersion: z.string().nullish(),
  })
  .strip() satisfies z.ZodType<ParkingCandidate>;

export const inventoryStatusSchema = z
  .object({
    community: inventoryChannelStatusSchema,
    municipal: inventoryChannelStatusSchema,
  })
  .strip() satisfies z.ZodType<InventoryStatus>;

export const recommendationDestinationInputSchema = z
  .object({
    label: z.string().min(1).max(512),
    latitude: finiteNumberSchema.min(-90).max(90),
    longitude: finiteNumberSchema.min(-180).max(180),
    source: destinationSourceSchema.nullish(),
    placeIdentity: placeIdentityInputSchema.nullish(),
    subtitle: z.string().max(256).nullish(),
  })
  .strip() satisfies z.ZodType<RecommendationDestinationInput>;

export const recommendationRequestSchema = z
  .object({
    destination: recommendationDestinationInputSchema,
    radiusMeters: z.number().int().min(1).max(5000).nullish(),
    limit: z.number().int().min(1).max(50).nullish(),
    includeCommunity: z.boolean().nullish(),
    includeMunicipal: z.boolean().nullish(),
  })
  .strip() satisfies z.ZodType<RecommendationRequest>;

export const rankingEvaluationOutcomeTypeSchema = z.enum([
  'RECOMMENDATION_SELECTED',
  'NAVIGATION_STARTED',
  'PARKING_SESSION_STARTED',
  'RETURN_TO_CAR_STARTED',
  'PARKING_SESSION_ENDED',
]);

export const rankingEvaluationPlatformSchema = z.enum(['WEB', 'MOBILE_V2', 'UNKNOWN']);

export const rankingEvaluationOutcomeRequestSchema = z
  .object({
    evaluationId: z.string().uuid(),
    candidateOrdinal: z.number().int().nonnegative(),
    outcomeType: rankingEvaluationOutcomeTypeSchema,
    platform: rankingEvaluationPlatformSchema.nullish(),
    latencyBucket: z.string().nullish(),
  })
  .strip() satisfies z.ZodType<RankingEvaluationOutcomeRequest>;

export const rankingEvaluationOutcomeResponseSchema = z
  .object({
    status: z.string().min(1),
  })
  .strip() satisfies z.ZodType<RankingEvaluationOutcomeResponse>;

export const recommendationResponseSchema = z
  .object({
    destination: destinationSchema,
    generatedAt: z.string().min(1),
    partial: z.boolean(),
    inventoryStatus: inventoryStatusSchema,
    candidates: z.array(parkingCandidateSchema),
    warnings: z.array(recommendationReasonSchema).nullish(),
    rankingVersion: rankingVersionSchema.nullish(),
    rankingStatus: rankingStatusSchema.nullish(),
    evaluationId: z.string().uuid().nullish(),
  })
  .strip() satisfies z.ZodType<RecommendationResponse>;
