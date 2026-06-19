import {
  isUnreadNotification,
  type AppNotification,
  type NotificationType,
} from '@parkio/types';
import {
  Button,
  EmptyState,
  Icon,
  LoadingState,
  SoftBadge,
  Surface,
  cn,
  type BadgeTone,
} from '@parkio/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { notificationsApi } from '@/api';
import { ComingSoonControl } from '@/components/ComingSoonControl';
import { FriendlyApiErrorMessage } from '@/components/FriendlyApiErrorMessage';
import { formatInstant, formatRelativeAgo, humanizeEnum } from '@/lib/format';
import { showError, showSuccess } from '@/lib/toast';

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
        {/*
         * Visual-only: the notification-service exposes no bulk "mark all read"
         * endpoint, so this is intentionally inert (mark items read individually).
         * Shown only when something is unread so it stays contextually honest.
         */}
        {query.data && query.data.some(isUnreadNotification) ? (
          <ComingSoonControl
            icon="done_all"
            explanation="Mark notifications as read individually until the bulk endpoint is available."
          >
            Mark all as read
          </ComingSoonControl>
        ) : null}
      </header>

      {query.isPending ? (
        <Surface level="card" className="p-lg">
          <LoadingState />
        </Surface>
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
  const visual = typeVisual(notification.type);

  const markRead = useMutation({
    mutationFn: () => notificationsApi.markRead(notification.id),
    onSuccess: () => {
      showSuccess('Notification marked as read.');
      void queryClient.invalidateQueries({ queryKey: ['notifications'] });
    },
    onError: () => showError('Could not mark notification as read.'),
  });

  return (
    <li
      className={cn(
        'flex gap-sm rounded-xl p-sm transition-colors duration-std',
        unread
          ? 'border-l-4 border-primary bg-surface-container-lowest shadow-sm'
          : 'bg-surface-container-low/50',
      )}
    >
      <span
        className={cn(
          'mt-[2px] flex h-9 w-9 shrink-0 items-center justify-center rounded-full',
          unread ? 'bg-primary/10 text-primary' : 'bg-surface-container-high text-on-surface-variant',
        )}
      >
        <Icon name={visual.icon} className="text-[18px] leading-none" />
      </span>

      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-xs">
          <strong
            className={cn(
              'min-w-0 flex-1 truncate text-body-md',
              unread ? 'text-on-surface' : 'font-normal text-on-surface-variant',
            )}
          >
            {notification.title}
          </strong>
          {unread ? <span className="h-2 w-2 shrink-0 rounded-full bg-primary" aria-hidden /> : null}
        </div>

        <p
          className={cn(
            'm-0 mt-[2px] text-body-md',
            unread ? 'text-on-surface-variant' : 'text-on-surface-variant/80',
          )}
        >
          {notification.body}
        </p>

        <div className="mt-xs flex flex-wrap items-center gap-x-sm gap-y-xs text-label-sm text-on-surface-variant">
          <SoftBadge tone={visual.tone} icon={visual.icon}>
            {humanizeEnum(notification.type)}
          </SoftBadge>
          <span>
            {formatRelativeAgo(notification.createdAt)}
            {notification.readAt ? ` · Read ${formatInstant(notification.readAt)}` : ''}
          </span>
        </div>

        {unread ? (
          <div className="mt-sm">
            <Button variant="ghost" onClick={() => markRead.mutate()} disabled={markRead.isPending}>
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
