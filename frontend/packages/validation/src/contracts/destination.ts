import { z } from 'zod';
import type { Destination, PlaceIdentity } from '@parkio/types';
import { finiteNumberSchema } from './primitives';

export const placeIdentitySchema = z
  .object({
    provider: z.string().min(1),
    providerPlaceId: z.string().min(1),
    canonicalKey: z.string().min(1),
  })
  .strip() satisfies z.ZodType<PlaceIdentity>;

export const destinationSourceSchema = z.enum(['GEOCODING', 'MAP_PIN', 'SYSTEM']);

export const destinationSchema = z
  .object({
    label: z.string().min(1).max(512),
    latitude: finiteNumberSchema.min(-90).max(90),
    longitude: finiteNumberSchema.min(-180).max(180),
    source: destinationSourceSchema,
    placeIdentity: placeIdentitySchema.nullish(),
    subtitle: z.string().max(256).nullish(),
  })
  .strip() satisfies z.ZodType<Destination>;
