import { isUnreadNotification } from '@parkio/types';
import { useQuery } from '@tanstack/react-query';
import { notificationsApi } from '@/api';

/**
 * Unread count derived from the cached `['notifications']` list — the backend has
 * no dedicated unread-count endpoint, so a separate key would double-fetch.
 */
export function UnreadBadge({ className }: { className?: string }) {
  const unreadCount = useQuery({
    queryKey: ['notifications'],
    queryFn: notificationsApi.getMyNotifications,
    select: (notifications) => notifications.filter(isUnreadNotification).length,
  });

  if (!unreadCount.isSuccess || unreadCount.data === 0) return null;

  return (
    <span
      className={
        className ??
        'ml-xs rounded-full bg-primary px-1.5 py-px text-label-sm text-on-primary'
      }
    >
      {unreadCount.data}
    </span>
  );
}
