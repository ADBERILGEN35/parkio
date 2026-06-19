import { isUnreadNotification, type AppNotification } from '@parkio/types';
import {
  EmptyState,
  NotificationSkeleton,
  Surface,
  cn,
} from '@parkio/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { notificationsApi } from '@/api';
import { FriendlyApiErrorMessage } from '@/components/FriendlyApiErrorMessage';
import { MarkReadButton, NotificationItemCard } from '@/components/product/NotificationItemCard';
import { showError, showSuccess } from '@/lib/toast';

/**
 * Filter chips are a frontend-only view over the already-fetched list — they
 * group the real backend `NotificationType`s, they do not introduce new
 * categories or call new endpoints. "Moderation" = WARNING; "Gamification" =
 * LEVEL_UP/POINT_EARNED.
 */
type NotificationFilter = 'all' | 'unread' | 'moderation' | 'gamification';

const FILTERS: { id: NotificationFilter; label: string }[] = [
  { id: 'all', label: 'All activity' },
  { id: 'unread', label: 'Unread' },
  { id: 'moderation', label: 'Moderation' },
  { id: 'gamification', label: 'Gamification' },
];

function matchesFilter(notification: AppNotification, filter: NotificationFilter): boolean {
  switch (filter) {
    case 'all':
      return true;
    case 'unread':
      return isUnreadNotification(notification);
    case 'moderation':
      return notification.type === 'WARNING';
    case 'gamification':
      return notification.type === 'LEVEL_UP' || notification.type === 'POINT_EARNED';
  }
}

export function NotificationsPage() {
  const query = useQuery({
    queryKey: ['notifications'],
    queryFn: notificationsApi.getMyNotifications,
  });

  return (
    <div className="mx-auto w-full max-w-3xl px-md py-lg text-on-background md:px-xl">
      <header className="mb-lg flex flex-wrap items-end justify-between gap-sm">
        <div className="min-w-0">
          <h1 className="m-0 text-headline-lg-mobile text-on-surface md:text-headline-lg">
            Notifications
          </h1>
          <p className="m-0 mt-xs text-body-md text-on-surface-variant">
            Manage your alerts and activity updates.
          </p>
        </div>
      </header>

      {query.isPending ? (
        <NotificationSkeleton />
      ) : query.isError ? (
        <Surface level="card" className="p-lg">
          <FriendlyApiErrorMessage error={query.error} />
        </Surface>
      ) : query.data.length === 0 ? (
        <Surface level="card" className="p-lg">
          <EmptyState
            icon="notifications_off"
            title="No notifications yet"
            description="Activity on your spots and points will show up here."
          />
        </Surface>
      ) : (
        <NotificationsBoard notifications={query.data} />
      )}
    </div>
  );
}

function NotificationsBoard({ notifications }: { notifications: AppNotification[] }) {
  const [filter, setFilter] = useState<NotificationFilter>('all');

  const counts: Record<NotificationFilter, number> = {
    all: notifications.length,
    unread: notifications.filter((n) => matchesFilter(n, 'unread')).length,
    moderation: notifications.filter((n) => matchesFilter(n, 'moderation')).length,
    gamification: notifications.filter((n) => matchesFilter(n, 'gamification')).length,
  };

  const visible = notifications.filter((n) => matchesFilter(n, filter));
  const unread = visible.filter(isUnreadNotification);
  const read = visible.filter((n) => !isUnreadNotification(n));

  return (
    <div className="flex flex-col gap-md">
      {/* Filter chips — horizontal scroll on narrow screens */}
      <div className="-mx-md flex gap-sm overflow-x-auto px-md hide-scrollbar" role="group" aria-label="Filter notifications">
        {FILTERS.map((option) => {
          const selected = filter === option.id;
          return (
            <button
              key={option.id}
              type="button"
              aria-pressed={selected}
              onClick={() => setFilter(option.id)}
              className={cn(
                'inline-flex shrink-0 items-center gap-xs rounded-full px-md py-1.5 text-label-md transition-colors duration-std',
                'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary',
                selected
                  ? 'border border-primary/20 bg-primary/10 text-primary'
                  : 'border border-outline-variant/40 bg-surface-container-lowest text-on-surface-variant hover:bg-surface-container',
              )}
            >
              <span>{option.label}</span>
              <span
                className={cn(
                  'rounded-full px-xs text-label-sm',
                  selected ? 'bg-primary/15 text-primary' : 'bg-surface-container-high text-on-surface-variant',
                )}
              >
                {counts[option.id]}
              </span>
            </button>
          );
        })}
      </div>

      {visible.length === 0 ? (
        <Surface level="card" className="p-lg">
          <EmptyState
            icon="filter_alt_off"
            title="Nothing in this filter"
            description="Try a different filter to see more of your activity."
          />
        </Surface>
      ) : (
        <Surface level="card" className="flex flex-col gap-md p-md md:p-lg">
          {unread.length > 0 ? (
            <Group label="New" count={unread.length}>
              {unread.map((n) => (
                <NotificationItem key={n.id} notification={n} />
              ))}
            </Group>
          ) : null}

          {read.length > 0 ? (
            <Group label="Earlier" count={read.length}>
              {read.map((n) => (
                <NotificationItem key={n.id} notification={n} />
              ))}
            </Group>
          ) : null}
        </Surface>
      )}

      <p className="m-0 px-xs text-label-sm text-on-surface-variant/80">
        Showing your most recent notifications (up to 50). Pagination isn't available yet.
      </p>
    </div>
  );
}

function Group({ label, count, children }: { label: string; count: number; children: ReactNode }) {
  return (
    <section>
      <div className="mb-sm flex items-center gap-sm">
        <p className="m-0 text-label-sm font-semibold uppercase tracking-wider text-on-surface-variant">
          {label}
        </p>
        <span className="h-px flex-1 bg-outline-variant/30" aria-hidden />
        <span className="text-label-sm text-on-surface-variant">{count}</span>
      </div>
      <ul className="m-0 flex list-none flex-col gap-xs p-0">{children}</ul>
    </section>
  );
}

function NotificationItem({ notification }: { notification: AppNotification }) {
  const queryClient = useQueryClient();
  const unread = isUnreadNotification(notification);

  const markRead = useMutation({
    mutationFn: () => notificationsApi.markRead(notification.id),
    onSuccess: (updated) => {
      queryClient.setQueryData<AppNotification[]>(['notifications'], (current) =>
        current?.map((item) => (item.id === updated.id ? updated : item)),
      );
      showSuccess('Notification marked as read.');
    },
    onError: () => showError('Could not mark notification as read.'),
  });

  return (
    <NotificationItemCard
      notification={notification}
      action={
        unread ? (
          <MarkReadButton onClick={() => markRead.mutate()} pending={markRead.isPending} />
        ) : null
      }
      error={markRead.isError ? <FriendlyApiErrorMessage error={markRead.error} /> : null}
    />
  );
}
