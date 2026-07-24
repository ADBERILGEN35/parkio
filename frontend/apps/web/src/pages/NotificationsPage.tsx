import { isUnreadNotification, type AppNotification } from '@parkio/types';
import {
  EmptyState,
  Icon,
  NotificationSkeleton,
  Surface,
  cn,
} from '@parkio/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { useParkioSdk } from '@/app/AppRuntimeContext';
import { FriendlyApiErrorMessage } from '@/components/FriendlyApiErrorMessage';
import { MarkReadButton, NotificationItemCard } from '@/components/product/NotificationItemCard';
import { showError, showSuccess } from '@/lib/toast';
import { resolveNotificationNavigation } from '@/lib/notificationDeepLinks';
import { useAuthStore } from '@/auth/store';

/**
 * Filter chips are a frontend-only view over the already-fetched list — they
 * group the real backend `NotificationType`s, they do not introduce new
 * categories or call new endpoints. "Moderation" = WARNING; "Gamification" =
 * LEVEL_UP/POINT_EARNED.
 */
type NotificationFilter = 'all' | 'unread' | 'moderation' | 'gamification';

const FILTER_IDS: NotificationFilter[] = ['all', 'unread', 'moderation', 'gamification'];

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
  const { notificationsApi } = useParkioSdk();
  const { t } = useTranslation('parking');
  const query = useQuery({
    queryKey: ['notifications'],
    queryFn: notificationsApi.getMyNotifications,
  });

  return (
    <div className="mx-auto w-full max-w-3xl min-w-0 px-md py-lg text-on-background md:px-xl">
      <header className="mb-lg flex min-w-0 flex-wrap items-end justify-between gap-sm">
        <div className="min-w-0">
          <h1 className="m-0 break-words text-headline-lg-mobile text-on-surface md:text-headline-lg">
            {t('notifications.title')}
          </h1>
          <p className="m-0 mt-xs text-body-md text-on-surface-variant">
            {t('notifications.description')}
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
            title={t('notifications.emptyTitle')}
            description={t('notifications.emptyDescription')}
          />
        </Surface>
      ) : (
        <NotificationsBoard notifications={query.data} />
      )}
    </div>
  );
}

function NotificationsBoard({ notifications }: { notifications: AppNotification[] }) {
  const { t } = useTranslation('parking');
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
      {/* Filter chips — contained horizontal scroller (no document overflow) */}
      <div
        className="flex w-full min-w-0 gap-sm overflow-x-auto overscroll-x-contain hide-scrollbar [-webkit-overflow-scrolling:touch]"
        role="group"
        aria-label={t('notifications.filterAria')}
      >
        {FILTER_IDS.map((id) => {
          const selected = filter === id;
          return (
            <button
              key={id}
              type="button"
              aria-pressed={selected}
              onClick={() => setFilter(id)}
              className={cn(
                'inline-flex shrink-0 items-center gap-xs rounded-full px-md py-2.5 min-h-11 text-label-md transition-colors duration-std',
                'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary',
                selected
                  ? 'border border-primary/20 bg-primary/10 text-primary'
                  : 'border border-outline-variant/40 bg-surface-container-lowest text-on-surface-variant hover:bg-surface-container',
              )}
            >
              <span>{t(`notifications.filters.${id}`)}</span>
              <span
                className={cn(
                  'rounded-full px-xs text-label-sm',
                  selected ? 'bg-primary/15 text-primary' : 'bg-surface-container-high text-on-surface-variant',
                )}
              >
                {counts[id]}
              </span>
            </button>
          );
        })}
      </div>

      {visible.length === 0 ? (
        <Surface level="card" className="p-lg">
          <EmptyState
            icon="filter_alt_off"
            title={t('notifications.filterEmptyTitle')}
            description={t('notifications.filterEmptyDescription')}
          />
        </Surface>
      ) : (
        <Surface level="card" className="flex flex-col gap-md p-md md:p-lg">
          {unread.length > 0 ? (
            <Group label={t('notifications.groupNew')} count={unread.length}>
              {unread.map((n) => (
                <NotificationItem key={n.id} notification={n} />
              ))}
            </Group>
          ) : null}

          {read.length > 0 ? (
            <Group label={t('notifications.groupEarlier')} count={read.length}>
              {read.map((n) => (
                <NotificationItem key={n.id} notification={n} />
              ))}
            </Group>
          ) : null}
        </Surface>
      )}

      <p className="m-0 px-xs text-label-sm text-on-surface-variant/80">{t('notifications.footer')}</p>
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
  const { notificationsApi } = useParkioSdk();
  const { t } = useTranslation('parking');
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const roles = useAuthStore((s) => s.roles);
  const unread = isUnreadNotification(notification);

  const markRead = useMutation({
    mutationFn: () => notificationsApi.markRead(notification.id),
    onSuccess: (updated) => {
      queryClient.setQueryData<AppNotification[]>(['notifications'], (current) =>
        current?.map((item) => (item.id === updated.id ? updated : item)),
      );
      showSuccess(t('notifications.markReadSuccess'));
    },
    onError: () => showError(t('notifications.markReadError')),
  });

  const smartReturnAction = smartReturnNotificationAction(notification, navigate, roles, t);
  const action = smartReturnAction || unread ? (
    <div className="flex flex-wrap gap-sm">
      {smartReturnAction}
      {unread ? (
        <MarkReadButton onClick={() => markRead.mutate()} pending={markRead.isPending} />
      ) : null}
    </div>
  ) : null;

  return (
    <NotificationItemCard
      notification={notification}
      action={action}
      error={markRead.isError ? <FriendlyApiErrorMessage error={markRead.error} /> : null}
    />
  );
}

function smartReturnNotificationAction(
  notification: AppNotification,
  navigate: (to: string) => void,
  roles: string[],
  t: (key: string) => string,
): ReactNode {
  if (notification.type === 'SMART_RETURN_PROMPT') {
    return (
      <button
        type="button"
        onClick={() => navigate(resolveNotificationNavigation(notification.metadata?.deeplink, notification.type, roles))}
        className="inline-flex min-h-11 w-full items-center justify-center gap-xs rounded-full bg-primary px-lg py-sm text-label-md font-semibold text-on-primary transition-colors hover:bg-primary/90 focus:outline-none focus-visible:ring-2 focus-visible:ring-primary sm:w-auto"
      >
        <Icon name="directions_car" className="text-[18px] leading-none" />
        {t('notifications.setTodaysReturn')}
      </button>
    );
  }
  if (notification.type === 'SMART_RETURN_AVAILABLE') {
    return (
      <button
        type="button"
        onClick={() => navigate(resolveNotificationNavigation(notification.metadata?.deeplink, notification.type, roles))}
        className="inline-flex min-h-11 w-full items-center justify-center gap-xs rounded-full bg-primary px-lg py-sm text-label-md font-semibold text-on-primary transition-colors hover:bg-primary/90 focus:outline-none focus-visible:ring-2 focus-visible:ring-primary sm:w-auto"
      >
        <Icon name="map" className="text-[18px] leading-none" />
        {t('notifications.openMap')}
      </button>
    );
  }
  return null;
}
