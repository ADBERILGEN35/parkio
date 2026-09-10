import { z } from 'zod';
import type {
  ConfirmRecentDestinationRequest,
  RecentDestination,
  RecentDestinationListResponse,
  RecentParking,
  RecentParkingListResponse,
  RecordRecentParkingRequest,
} from '@parkio/types';
import { destinationSourceSchema, placeIdentitySchema } from './destination';
import { finiteNumberSchema } from './primitives';

export const recentParkingTargetKindSchema = z.enum(['MUNICIPAL_FACILITY']);

export const recentDestinationSchema = z
  .object({
    id: z.string().uuid(),
    label: z.string().min(1).max(512),
    latitude: finiteNumberSchema.min(-90).max(90),
    longitude: finiteNumberSchema.min(-180).max(180),
    source: destinationSourceSchema,
    placeIdentity: placeIdentitySchema.nullish(),
    subtitle: z.string().max(256).nullish(),
    firstUsedAt: z.string().min(1),
    lastUsedAt: z.string().min(1),
    useCount: z.number().int().positive(),
  })
  .strip() satisfies z.ZodType<RecentDestination>;

export const recentDestinationListResponseSchema = z
  .object({
    items: z.array(recentDestinationSchema),
  })
  .strip() satisfies z.ZodType<RecentDestinationListResponse>;

export const confirmRecentDestinationRequestSchema = z
  .object({
    label: z.string().min(1).max(512),
    latitude: finiteNumberSchema.min(-90).max(90),
    longitude: finiteNumberSchema.min(-180).max(180),
    source: destinationSourceSchema.nullish(),
    placeIdentity: placeIdentitySchema.pick({ provider: true, providerPlaceId: true }).strip().nullish(),
    subtitle: z.string().max(256).nullish(),
  })
  .strip() satisfies z.ZodType<ConfirmRecentDestinationRequest>;

export const recentParkingSchema = z
  .object({
    id: z.string().uuid(),
    targetKind: recentParkingTargetKindSchema,
    targetId: z.string().uuid(),
    firstUsedAt: z.string().min(1),
    lastUsedAt: z.string().min(1),
    useCount: z.number().int().positive(),
  })
  .strip() satisfies z.ZodType<RecentParking>;

export const recentParkingListResponseSchema = z
  .object({
    items: z.array(recentParkingSchema),
  })
  .strip() satisfies z.ZodType<RecentParkingListResponse>;

export const recordRecentParkingRequestSchema = z
  .object({
    targetKind: recentParkingTargetKindSchema.nullish(),
    targetId: z.string().uuid(),
  })
  .strip() satisfies z.ZodType<RecordRecentParkingRequest>;
