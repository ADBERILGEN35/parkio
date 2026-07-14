import { isUnreadNotification, type AppNotification, type NotificationType } from '@parkio/types';
import { Button, Icon, SoftBadge, cn, type BadgeTone } from '@parkio/ui';
import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { enumLabel, formatInstant, formatRelativeAgo } from '@/lib/format';
import { localizeNotification } from '@/lib/localizeNotification';

export const NOTIFICATION_TYPE_VISUALS: Record<NotificationType, { icon: string; tone: BadgeTone }> = {
  NEARBY_PARKING: { icon: 'local_parking', tone: 'primary' },
  LEVEL_UP: { icon: 'military_tech', tone: 'success' },
  POINT_EARNED: { icon: 'stars', tone: 'success' },
  WARNING: { icon: 'warning', tone: 'warning' },
  SYSTEM: { icon: 'info', tone: 'neutral' },
  SMART_RETURN_PROMPT: { icon: 'directions_car', tone: 'primary' },
  SMART_RETURN_AVAILABLE: { icon: 'home_pin', tone: 'success' },
};

function typeVisual(type: NotificationType) {
  return NOTIFICATION_TYPE_VISUALS[type] ?? { icon: 'notifications', tone: 'neutral' as BadgeTone };
}

export interface NotificationItemCardProps {
  notification: AppNotification;
  action?: ReactNode;
  error?: ReactNode;
}

export function NotificationItemCard({ notification, action, error }: NotificationItemCardProps) {
  const { t } = useTranslation(['parking', 'common']);
  const unread = isUnreadNotification(notification);
  const visual = typeVisual(notification.type);
  const { title, body } = localizeNotification(notification);
  const typeLabel = enumLabel(notification.type, t, ['notificationType']);

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
            {title}
          </strong>
          {unread ? <span className="h-2 w-2 shrink-0 rounded-full bg-primary" aria-hidden /> : null}
        </div>

        <p
          className={cn(
            'm-0 mt-[2px] text-body-md',
            unread ? 'text-on-surface-variant' : 'text-on-surface-variant/80',
          )}
        >
          {body}
        </p>

        <div className="mt-xs flex flex-wrap items-center gap-x-sm gap-y-xs text-label-sm text-on-surface-variant">
          <SoftBadge tone={visual.tone} icon={visual.icon}>
            {typeLabel}
          </SoftBadge>
          <span>
            {formatRelativeAgo(notification.createdAt)}
            {notification.readAt
              ? t('parking:notifications.readAt', { when: formatInstant(notification.readAt) })
              : ''}
          </span>
        </div>

        {action ? <div className="mt-sm">{action}</div> : null}
        {error ? <div className="mt-sm">{error}</div> : null}
      </div>
    </li>
  );
}

export function MarkReadButton({
  onClick,
  pending,
}: {
  onClick: () => void;
  pending: boolean;
}) {
  const { t } = useTranslation('parking');
  return (
    <Button variant="ghost" onClick={onClick} disabled={pending}>
      {pending ? t('notifications.marking') : t('notifications.markAsRead')}
    </Button>
  );
}
