/** What a notification is about — mirrors notification-service `NotificationType`. */
export const NOTIFICATION_TYPES = [
  'NEARBY_PARKING',
  'LEVEL_UP',
  'POINT_EARNED',
  'WARNING',
  'SYSTEM',
] as const;

export type NotificationType = (typeof NOTIFICATION_TYPES)[number];

/** How a notification is delivered — mirrors notification-service `NotificationChannel`. */
export const NOTIFICATION_CHANNELS = ['PUSH', 'EMAIL', 'IN_APP'] as const;

export type NotificationChannel = (typeof NOTIFICATION_CHANNELS)[number];

/** Delivery/read lifecycle — mirrors notification-service `NotificationStatus`. */
export const NOTIFICATION_STATUSES = ['PENDING', 'SENT', 'FAILED', 'READ'] as const;

export type NotificationStatus = (typeof NOTIFICATION_STATUSES)[number];

/**
 * A notification as shown to its recipient — mirrors `NotificationResponse`.
 * `readAt` is null until the notification is marked read (status `READ`).
 */
export interface AppNotification {
  id: string;
  type: NotificationType;
  channel: NotificationChannel;
  title: string;
  body: string;
  status: NotificationStatus;
  createdAt: string;
  readAt: string | null;
}

/** Backend DTO name alias. */
export type NotificationResponse = AppNotification;

/** A notification counts as unread until the recipient marks it read. */
export function isUnreadNotification(notification: AppNotification): boolean {
  return notification.status !== 'READ';
}
