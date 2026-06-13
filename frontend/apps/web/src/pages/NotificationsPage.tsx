import {
  isUnreadNotification,
  type AppNotification,
  type NotificationType,
} from '@parkio/types';
import {
  Button,
  Card,
  EmptyState,
  Icon,
  LoadingState,
  PageShell,
  SoftBadge,
  cn,
  type BadgeTone,
} from '@parkio/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { notificationsApi } from '@/api';
import { FriendlyApiErrorMessage } from '@/components/FriendlyApiErrorMessage';
import { AppNav } from '@/components/AppNav';
import { formatInstant, formatRelativeAgo, humanizeEnum } from '@/lib/format';

/** Type → icon + tone (NotificationType is backend-provided; no invented categories). */
const TYPE_VISUALS: Record<NotificationType, { icon: string; tone: BadgeTone }> = {
  NEARBY_PARKING: { icon: 'local_parking', tone: 'primary' },
  LEVEL_UP: { icon: 'military_tech', tone: 'success' },
  POINT_EARNED: { icon: 'stars', tone: 'success' },
  WARNING: { icon: 'warning', tone: 'warning' },
  SYSTEM: { icon: 'info', tone: 'neutral' },
};

function typeVisual(type: NotificationType) {
  return TYPE_VISUALS[type] ?? { icon: 'notifications', tone: 'neutral' as BadgeTone };
}

export function NotificationsPage() {
  const query = useQuery({
    queryKey: ['notifications'],
    queryFn: notificationsApi.getMyNotifications,
  });

  return (
    <PageShell title="Notifications">
      <AppNav />

      {query.isPending ? (
        <Card title="Inbox">
          <LoadingState />
        </Card>
      ) : query.isError ? (
        <Card title="Inbox">
          <FriendlyApiErrorMessage error={query.error} />
        </Card>
      ) : query.data.length === 0 ? (
        <Card title="Inbox">
          <EmptyState
            icon="notifications_off"
            title="No notifications yet"
            description="Activity on your spots and points will show up here."
          />
        </Card>
      ) : (
        <NotificationsList notifications={query.data} />
      )}
    </PageShell>
  );
}

function NotificationsList({ notifications }: { notifications: AppNotification[] }) {
  const unread = notifications.filter(isUnreadNotification);
  const read = notifications.filter((n) => !isUnreadNotification(n));

  return (
    <Card>
      <div className="mb-md flex items-center justify-between gap-sm">
        <h2 className="m-0 text-title-lg text-on-surface">Inbox</h2>
        {unread.length > 0 ? (
          <SoftBadge tone="primary" icon="mark_email_unread">
            {unread.length} unread
          </SoftBadge>
        ) : (
          <SoftBadge tone="success" icon="done_all">
            All read
          </SoftBadge>
        )}
      </div>

      {unread.length > 0 ? (
        <Group label="New">
          {unread.map((n) => (
            <NotificationItem key={n.id} notification={n} />
          ))}
        </Group>
      ) : null}

      {read.length > 0 ? (
        <Group label="Earlier">
          {read.map((n) => (
            <NotificationItem key={n.id} notification={n} />
          ))}
        </Group>
      ) : null}

      <p className="m-0 mt-md text-label-sm text-on-surface-variant">
        Showing your most recent notifications (up to 50) — the backend does not paginate this
        list yet.
      </p>
    </Card>
  );
}

function Group({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="mb-md">
      <p className="m-0 mb-sm text-label-sm font-semibold uppercase tracking-wider text-on-surface-variant">
        {label}
      </p>
      <ul className="m-0 flex list-none flex-col gap-sm p-0">{children}</ul>
    </div>
  );
}

function NotificationItem({ notification }: { notification: AppNotification }) {
  const queryClient = useQueryClient();
  const unread = isUnreadNotification(notification);
  const visual = typeVisual(notification.type);

  const markRead = useMutation({
    mutationFn: () => notificationsApi.markRead(notification.id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['notifications'] }),
  });

  return (
    <li
      className={cn(
        'flex gap-sm rounded-xl border p-md transition-colors duration-std',
        unread
          ? 'border-l-4 border-primary bg-surface-container-lowest'
          : 'border-outline-variant/40 bg-surface-container-low',
      )}
    >
      <span
        className={cn(
          'flex h-10 w-10 shrink-0 items-center justify-center rounded-full',
          unread ? 'bg-primary/10 text-primary' : 'bg-surface-container-high text-on-surface-variant',
        )}
      >
        <Icon name={visual.icon} className="text-[20px] leading-none" />
      </span>

      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-sm">
          <strong className={cn('text-body-md', unread ? 'text-on-surface' : 'text-on-surface-variant')}>
            {notification.title}
          </strong>
          {unread ? <span className="h-2 w-2 rounded-full bg-primary" aria-hidden /> : null}
          <SoftBadge tone={unread ? 'primary' : 'neutral'}>{unread ? 'Unread' : 'Read'}</SoftBadge>
        </div>

        <p className={cn('m-0 mt-xs text-body-md', unread ? 'text-on-surface' : 'text-on-surface-variant')}>
          {notification.body}
        </p>

        <div className="mt-sm flex flex-wrap items-center gap-sm">
          <SoftBadge tone={visual.tone} icon={visual.icon}>
            {humanizeEnum(notification.type)}
          </SoftBadge>
          <span className="text-label-sm text-on-surface-variant">
            {formatRelativeAgo(notification.createdAt)}
            {notification.readAt ? ` · Read ${formatInstant(notification.readAt)}` : ''}
          </span>
        </div>

        {unread ? (
          <div className="mt-sm">
            <Button variant="secondary" onClick={() => markRead.mutate()} disabled={markRead.isPending}>
              {markRead.isPending ? 'Marking…' : 'Mark as read'}
            </Button>
          </div>
        ) : null}
        {markRead.isError ? (
          <div className="mt-sm">
            <FriendlyApiErrorMessage error={markRead.error} />
          </div>
        ) : null}
      </div>
    </li>
  );
}
