import { z } from 'zod';
import {
  DEVICE_PLATFORMS,
  NOTIFICATION_CHANNELS,
  NOTIFICATION_STATUSES,
  NOTIFICATION_TYPES,
  type AppNotification,
  type DeviceToken,
  type RegisterDeviceTokenRequest,
} from '@parkio/types';
import { instantSchema, uuidSchema } from './primitives';

export const registerDeviceTokenRequestSchema = z
  .object({
    token: z.string().min(1).max(512),
    platform: z.enum(DEVICE_PLATFORMS),
  })
  .strict() satisfies z.ZodType<RegisterDeviceTokenRequest>;

export const notificationResponseSchema = z
  .object({
    id: uuidSchema,
    type: z.enum(NOTIFICATION_TYPES),
    channel: z.enum(NOTIFICATION_CHANNELS),
    title: z.string(),
    body: z.string(),
    metadata: z.record(z.string()),
    status: z.enum(NOTIFICATION_STATUSES),
    createdAt: instantSchema,
    readAt: instantSchema.nullable(),
  })
  .strip() satisfies z.ZodType<AppNotification>;

export const notificationListResponseSchema = z.array(notificationResponseSchema);

export const deviceTokenResponseSchema = z
  .object({
    id: uuidSchema,
    platform: z.enum(DEVICE_PLATFORMS),
    active: z.boolean(),
    createdAt: instantSchema,
  })
  .strip() satisfies z.ZodType<DeviceToken>;
