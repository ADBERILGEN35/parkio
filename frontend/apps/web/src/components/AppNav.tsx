import { isUnreadNotification } from '@parkio/types';
import { colors, radius, spacing } from '@parkio/ui';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { notificationsApi } from '@/api';

/** Minimal cross-page navigation until a real app shell is designed. */
export function AppNav() {
  return (
    <nav style={{ marginBottom: spacing.md, display: 'flex', gap: spacing.md, fontSize: '0.875rem', flexWrap: 'wrap' }}>
      <Link to="/map">Map</Link>
      <Link to="/upload">Share a spot</Link>
      <Link to="/my-spots">My spots</Link>
      <Link to="/notifications">
        Notifications
        <UnreadBadge />
      </Link>
      <Link to="/gamification">Progress</Link>
      <Link to="/leaderboard">Leaderboard</Link>
      <Link to="/profile">Profile</Link>
    </nav>
  );
}

/**
 * Unread count derived from the cached `['notifications']` list — the backend has
 * no dedicated unread-count endpoint, so a separate key would double-fetch. No
 * polling: the query refetches only when a page mounts the nav (navigation/reload)
 * or the list is invalidated (mark-as-read).
 */
function UnreadBadge() {
  const unreadCount = useQuery({
    queryKey: ['notifications'],
    queryFn: notificationsApi.getMyNotifications,
    select: (notifications) => notifications.filter(isUnreadNotification).length,
  });

  if (!unreadCount.isSuccess || unreadCount.data === 0) return null;

  return (
    <span
      style={{
        marginLeft: spacing.xs,
        padding: `0 ${spacing.xs}`,
        borderRadius: radius.full,
        backgroundColor: colors.primary,
        color: colors.onPrimary,
        fontSize: '0.75rem',
      }}
    >
      {unreadCount.data}
    </span>
  );
}
