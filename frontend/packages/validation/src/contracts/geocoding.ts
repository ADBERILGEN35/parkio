import { z } from 'zod';
import type { GeocodeResult, GeocodeSearchResponse } from '@parkio/types';
import { finiteNumberSchema } from './primitives';

export const geocodeResultResponseSchema = z
  .object({
    id: z.string(),
    displayName: z.string(),
    primary: z.string(),
    secondary: z.string(),
    lat: finiteNumberSchema.min(-90).max(90),
    lng: finiteNumberSchema.min(-180).max(180),
  })
  .strip() satisfies z.ZodType<GeocodeResult>;

export const geocodeSearchResponseSchema = z
  .object({ results: z.array(geocodeResultResponseSchema) })
  .strip() satisfies z.ZodType<GeocodeSearchResponse>;
