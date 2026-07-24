import type { AxiosInstance } from 'axios';
import type { AppNotification, DevicePlatform, DeviceToken } from '@parkio/types';
import type { RequestOptions } from './request-options';

export function createNotificationsApi(client: AxiosInstance) {
  return {
    /** Most recent notifications (backend caps the list at 50 — no pagination params). */
    getMyNotifications(options?: RequestOptions): Promise<AppNotification[]> {
      return client
        .get<AppNotification[]>('/notifications/me', { signal: options?.signal })
        .then((r) => r.data);
    },

    /** Idempotent on the backend — re-marking a read notification is a no-op. */
    markRead(notificationId: string): Promise<AppNotification> {
      return client
        .patch<AppNotification>(`/notifications/${notificationId}/read`)
        .then((r) => r.data);
    },

    /** Register this device's push token (`POST /notifications/device-token`). */
    registerDeviceToken(token: string, platform: DevicePlatform): Promise<DeviceToken> {
      return client
        .post<DeviceToken>('/notifications/device-token', { token, platform })
        .then((r) => r.data);
    },

    /** Deactivate a previously registered push token (e.g. on logout). */
    deactivateDeviceToken(tokenId: string): Promise<void> {
      return client.delete(`/notifications/device-token/${tokenId}`).then(() => undefined);
    },
  };
}

export type NotificationsApi = ReturnType<typeof createNotificationsApi>;
