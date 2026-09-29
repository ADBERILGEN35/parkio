import { z } from 'zod';
import type { DestinationSearchItem, DestinationSearchSection } from '@parkio/types';
import { destinationSchema } from './destination';
import { savedPlaceKindSchema } from './saved-place';

export const destinationSearchSourceSchema = z.enum([
  'SAVED_PLACE',
  'FAVOURITE_DESTINATION',
  'RECENT_DESTINATION',
  'GEOCODING',
]);

export const destinationSearchItemSchema = z
  .object({
    id: z.string().min(1),
    source: destinationSearchSourceSchema,
    group: destinationSearchSourceSchema,
    destination: destinationSchema,
    title: z.string().min(1),
    subtitle: z.string().nullish(),
    savedPlaceKind: savedPlaceKindSchema.nullish(),
    alsoFavourite: z.boolean().optional(),
    alsoRecent: z.boolean().optional(),
  })
  .strip() satisfies z.ZodType<DestinationSearchItem>;

export const destinationSearchSectionSchema = z
  .object({
    group: destinationSearchSourceSchema,
    items: z.array(destinationSearchItemSchema),
  })
  .strip() satisfies z.ZodType<DestinationSearchSection>;
