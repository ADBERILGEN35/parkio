import { z } from 'zod';
import type {
  CreateCustomSavedPlaceRequest,
  SavedPlace,
  SavedPlaceListResponse,
  UpsertSavedPlaceRequest,
} from '@parkio/types';
import { destinationSourceSchema, placeIdentitySchema } from './destination';
import { finiteNumberSchema } from './primitives';

const placeIdentityInputSchema = placeIdentitySchema
  .pick({ provider: true, providerPlaceId: true })
  .strip();

export const savedPlaceKindSchema = z.enum(['HOME', 'WORK', 'CUSTOM']);

export const savedPlaceSchema = z
  .object({
    id: z.string().uuid(),
    kind: savedPlaceKindSchema,
    label: z.string().min(1).max(512),
    latitude: finiteNumberSchema.min(-90).max(90),
    longitude: finiteNumberSchema.min(-180).max(180),
    source: destinationSourceSchema,
    placeIdentity: placeIdentitySchema.nullish(),
    subtitle: z.string().max(256).nullish(),
    createdAt: z.string().min(1),
    updatedAt: z.string().min(1),
  })
  .strip() satisfies z.ZodType<SavedPlace>;

export const savedPlaceListResponseSchema = z
  .object({
    items: z.array(savedPlaceSchema),
  })
  .strip() satisfies z.ZodType<SavedPlaceListResponse>;

export const upsertSavedPlaceRequestSchema = z
  .object({
    latitude: finiteNumberSchema.min(-90).max(90),
    longitude: finiteNumberSchema.min(-180).max(180),
    label: z.string().max(512).nullish(),
    source: destinationSourceSchema.nullish(),
    placeIdentity: placeIdentityInputSchema.nullish(),
    subtitle: z.string().max(256).nullish(),
  })
  .strip() satisfies z.ZodType<UpsertSavedPlaceRequest>;

export const createCustomSavedPlaceRequestSchema = z
  .object({
    label: z.string().min(1).max(512),
    latitude: finiteNumberSchema.min(-90).max(90),
    longitude: finiteNumberSchema.min(-180).max(180),
    source: destinationSourceSchema.nullish(),
    placeIdentity: placeIdentityInputSchema.nullish(),
    subtitle: z.string().max(256).nullish(),
  })
  .strip() satisfies z.ZodType<CreateCustomSavedPlaceRequest>;
