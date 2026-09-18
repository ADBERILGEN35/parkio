import { z } from 'zod';
import type {
  CreateFavouriteDestinationRequest,
  CreateFavouriteParkingRequest,
  FavouriteDestination,
  FavouriteDestinationListResponse,
  FavouriteParking,
  FavouriteParkingListResponse,
  FavouriteParkingStatusResponse,
  UpdateFavouriteDestinationRequest,
} from '@parkio/types';
import { destinationSourceSchema, placeIdentitySchema } from './destination';
import { finiteNumberSchema } from './primitives';

const placeIdentityInputSchema = placeIdentitySchema
  .pick({ provider: true, providerPlaceId: true })
  .strip();

export const favouriteParkingTargetKindSchema = z.enum(['MUNICIPAL_FACILITY']);

export const favouriteParkingSchema = z
  .object({
    id: z.string().uuid(),
    targetKind: favouriteParkingTargetKindSchema,
    targetId: z.string().uuid(),
    createdAt: z.string().min(1),
  })
  .strip() satisfies z.ZodType<FavouriteParking>;

export const favouriteParkingListResponseSchema = z
  .object({
    items: z.array(favouriteParkingSchema),
  })
  .strip() satisfies z.ZodType<FavouriteParkingListResponse>;

export const createFavouriteParkingRequestSchema = z
  .object({
    targetKind: favouriteParkingTargetKindSchema.nullish(),
    targetId: z.string().uuid(),
  })
  .strip() satisfies z.ZodType<CreateFavouriteParkingRequest>;

export const favouriteParkingStatusResponseSchema = z
  .object({
    favouritedTargetIds: z.array(z.string().uuid()),
  })
  .strip() satisfies z.ZodType<FavouriteParkingStatusResponse>;

export const favouriteDestinationSchema = z
  .object({
    id: z.string().uuid(),
    label: z.string().min(1).max(512),
    latitude: finiteNumberSchema.min(-90).max(90),
    longitude: finiteNumberSchema.min(-180).max(180),
    source: destinationSourceSchema,
    placeIdentity: placeIdentitySchema.nullish(),
    subtitle: z.string().max(256).nullish(),
    createdAt: z.string().min(1),
    updatedAt: z.string().min(1),
  })
  .strip() satisfies z.ZodType<FavouriteDestination>;

export const favouriteDestinationListResponseSchema = z
  .object({
    items: z.array(favouriteDestinationSchema),
  })
  .strip() satisfies z.ZodType<FavouriteDestinationListResponse>;

export const createFavouriteDestinationRequestSchema = z
  .object({
    label: z.string().min(1).max(512),
    latitude: finiteNumberSchema.min(-90).max(90),
    longitude: finiteNumberSchema.min(-180).max(180),
    source: destinationSourceSchema.nullish(),
    placeIdentity: placeIdentityInputSchema.nullish(),
    subtitle: z.string().max(256).nullish(),
  })
  .strip() satisfies z.ZodType<CreateFavouriteDestinationRequest>;

export const updateFavouriteDestinationRequestSchema = z
  .object({
    label: z.string().min(1).max(512),
    subtitle: z.string().max(256).nullish(),
  })
  .strip() satisfies z.ZodType<UpdateFavouriteDestinationRequest>;
