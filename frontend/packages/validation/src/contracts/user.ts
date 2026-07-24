import { z } from 'zod';
import {
  SUPPORTED_LOCALES,
  VEHICLE_TYPES,
  type Profile,
  type PublicProfile,
  type SmartReturnSettings,
  type SmartReturnTodayRequest,
  type UpdatePreferenceRequest,
  type UpdateProfileRequest,
  type UpdateSmartReturnSettingsRequest,
  type UpsertVehicleRequest,
  type UserPreference,
  type UserStats,
  type VehicleProfile,
} from '@parkio/types';
import {
  finiteNumberSchema,
  instantSchema,
  integerSchema,
  localDateSchema,
  localTimeSchema,
  uuidSchema,
} from './primitives';

const userStatusSchema = z.enum(['ACTIVE', 'SUSPENDED', 'BANNED']);
const trustBandSchema = z.enum(['UNTRUSTED', 'LOW_TRUST', 'MEDIUM_TRUST', 'HIGH_TRUST']);
const smartReturnTodayStatusSchema = z.enum([
  'UNKNOWN',
  'LEFT_BY_CAR',
  'RETURN_CHECK_IN_PROGRESS',
  'NOT_BY_CAR',
  'CANCELLED',
]);

export const updateProfileRequestSchema = z
  .object({
    displayName: z.string().min(2).max(50).nullable().optional(),
    phoneNumber: z.string().max(32).nullable().optional(),
    city: z.string().max(100).nullable().optional(),
  })
  .strict() satisfies z.ZodType<UpdateProfileRequest>;

export const updatePreferenceRequestSchema = z
  .object({
    preferredRadiusMeters: integerSchema.min(100).max(50_000).nullable().optional(),
    notificationsEnabled: z.boolean().nullable().optional(),
    preferredLocale: z.enum(SUPPORTED_LOCALES).nullable().optional(),
  })
  .strict() satisfies z.ZodType<UpdatePreferenceRequest>;

export const updateSmartReturnSettingsRequestSchema = z
  .object({
    enabled: z.boolean().nullable().optional(),
    homeLatitude: finiteNumberSchema.min(-90).max(90).nullable().optional(),
    homeLongitude: finiteNumberSchema.min(-180).max(180).nullable().optional(),
    homeLabel: z.string().max(160).nullable().optional(),
    defaultReturnTime: localTimeSchema.nullable().optional(),
    reminderLeadMinutes: integerSchema.min(5).max(120).nullable().optional(),
  })
  .strict() satisfies z.ZodType<UpdateSmartReturnSettingsRequest>;

export const smartReturnTodayRequestSchema = z
  .object({ expectedReturnAt: instantSchema })
  .strict() satisfies z.ZodType<SmartReturnTodayRequest>;

export const upsertVehicleRequestSchema = z
  .object({
    vehicleType: z.enum(VEHICLE_TYPES).nullable().optional(),
    plate: z.string().max(16).nullable().optional(),
  })
  .strict() satisfies z.ZodType<UpsertVehicleRequest>;

export const profileResponseSchema = z
  .object({
    id: uuidSchema,
    authUserId: uuidSchema,
    email: z.string().email(),
    displayName: z.string().nullable(),
    phoneNumber: z.string().nullable(),
    city: z.string().nullable(),
    status: userStatusSchema,
    createdAt: instantSchema,
  })
  .strip() satisfies z.ZodType<Profile>;

export const userPreferenceResponseSchema = z
  .object({
    preferredRadiusMeters: integerSchema.min(100).max(50_000),
    notificationsEnabled: z.boolean(),
    preferredLocale: z.enum(SUPPORTED_LOCALES),
  })
  .strip() satisfies z.ZodType<UserPreference>;

export const smartReturnSettingsResponseSchema = z
  .object({
    enabled: z.boolean(),
    homeLatitude: finiteNumberSchema.min(-90).max(90).nullable(),
    homeLongitude: finiteNumberSchema.min(-180).max(180).nullable(),
    homeLabel: z.string().nullable(),
    defaultReturnTime: localTimeSchema.nullable(),
    reminderLeadMinutes: integerSchema.min(5).max(120),
    lastPromptDate: localDateSchema.nullable(),
    todayStatus: smartReturnTodayStatusSchema,
    todayExpectedReturnAt: instantSchema.nullable(),
    todayReturnCheckCompletedAt: instantSchema.nullable(),
    todayNotificationSentAt: instantSchema.nullable(),
  })
  .strip() satisfies z.ZodType<SmartReturnSettings>;

export const vehicleProfileResponseSchema = z
  .object({
    vehicleType: z.enum(VEHICLE_TYPES).nullable(),
    plate: z.string().nullable(),
  })
  .strip() satisfies z.ZodType<VehicleProfile>;

export const userStatsResponseSchema = z
  .object({
    trustScore: integerSchema,
    trustBand: trustBandSchema,
    totalPoints: integerSchema,
    currentLevel: integerSchema,
  })
  .strip() satisfies z.ZodType<UserStats>;

export const publicProfileResponseSchema = z
  .object({
    userId: uuidSchema,
    displayName: z.string().nullable(),
    city: z.string().nullable(),
    trustBand: trustBandSchema,
    currentLevel: integerSchema,
    status: userStatusSchema,
    memberSince: instantSchema,
  })
  .strip() satisfies z.ZodType<PublicProfile>;
