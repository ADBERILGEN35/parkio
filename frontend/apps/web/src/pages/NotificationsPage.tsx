import { isUnreadNotification, type AppNotification } from '@parkio/types';
import { Button, Card, LoadingState, PageShell, colors, radius, spacing } from '@parkio/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { notificationsApi } from '@/api';
import { ApiErrorMessage } from '@/components/ApiErrorMessage';
import { AppNav } from '@/components/AppNav';
import { formatInstant } from '@/lib/format';

export function NotificationsPage() {
  const query = useQuery({
    queryKey: ['notifications'],
    queryFn: notificationsApi.getMyNotifications,
  });

  return (
    <PageShell title="Notifications">
      <AppNav />

      <Card title="Inbox">
        {query.isPending ? (
          <LoadingState />
        ) : query.isError ? (
          <ApiErrorMessage error={query.error} />
        ) : query.data.length === 0 ? (
          <p style={{ margin: 0, color: colors.textMuted }}>
            No notifications yet — activity on your spots and points will show up here.
          </p>
        ) : (
          <>
            <ul
              style={{ listStyle: 'none', margin: 0, padding: 0, display: 'flex', flexDirection: 'column', gap: spacing.sm }}
            >
              {query.data.map((notification) => (
                <NotificationItem key={notification.id} notification={notification} />
              ))}
            </ul>
            <p style={{ margin: `${spacing.md} 0 0`, fontSize: '0.875rem', color: colors.textMuted }}>
              Showing your most recent notifications (up to 50) — the backend does not
              paginate this list yet.
            </p>
          </>
        )}
      </Card>
    </PageShell>
  );
}

function NotificationItem({ notification }: { notification: AppNotification }) {
  const queryClient = useQueryClient();
  const unread = isUnreadNotification(notification);

  const markRead = useMutation({
    mutationFn: () => notificationsApi.markRead(notification.id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['notifications'] }),
  });

  return (
    <li
      style={{
        padding: spacing.sm,
        border: `1px solid ${colors.border}`,
        borderRadius: radius.md,
        backgroundColor: unread ? colors.surface : colors.background,
      }}
    >
      <div style={{ display: 'flex', alignItems: 'baseline', gap: spacing.sm }}>
        <strong>{notification.title}</strong>
        <span
          style={{
            fontSize: '0.75rem',
            padding: `0 ${spacing.xs}`,
            borderRadius: radius.full,
            border: `1px solid ${unread ? colors.primary : colors.border}`,
            color: unread ? colors.primary : colors.textMuted,
          }}
        >
          {unread ? 'Unread' : 'Read'}
        </span>
      </div>
      <p style={{ margin: `${spacing.xs} 0 0` }}>{notification.body}</p>
      <p style={{ margin: `${spacing.xs} 0 0`, fontSize: '0.875rem', color: colors.textMuted }}>
        {notification.type} · {formatInstant(notification.createdAt)}
        {notification.readAt ? ` · Read ${formatInstant(notification.readAt)}` : ''}
      </p>
      {unread ? (
        <div style={{ marginTop: spacing.sm }}>
          <Button onClick={() => markRead.mutate()} disabled={markRead.isPending}>
            {markRead.isPending ? 'Marking…' : 'Mark as read'}
          </Button>
        </div>
      ) : null}
      {markRead.isError ? (
        <div style={{ marginTop: spacing.sm }}>
          <ApiErrorMessage error={markRead.error} />
        </div>
      ) : null}
    </li>
  );
}
