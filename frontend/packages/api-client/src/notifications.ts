import type { AxiosInstance } from 'axios';
import type { AppNotification } from '@parkio/types';

export function createNotificationsApi(client: AxiosInstance) {
  return {
    /** Most recent notifications (backend caps the list at 50 — no pagination params). */
    getMyNotifications(): Promise<AppNotification[]> {
      return client.get<AppNotification[]>('/notifications/me').then((r) => r.data);
    },

    /** Idempotent on the backend — re-marking a read notification is a no-op. */
    markRead(notificationId: string): Promise<AppNotification> {
      return client
        .patch<AppNotification>(`/notifications/${notificationId}/read`)
        .then((r) => r.data);
    },
  };
}

export type NotificationsApi = ReturnType<typeof createNotificationsApi>;
