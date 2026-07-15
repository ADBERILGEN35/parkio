import type { AppNotification } from '@parkio/types';

export interface LocalizedNotificationCopy {
  title: string;
  body: string;
  typeLabel?: string;
}

type TFn = (value: string) => string;

/**
 * Structured localization for in-app notification rows.
 * Prefer metadata (messageKey / variables) when present; fall back to stored
 * title/body so legacy English rows still render, then run through `t()`.
 *
 * Push banners use server-localized title/body; this helper re-renders the
 * in-app inbox (and can re-render from push `data.messageKey` if needed).
 */
export function localizeNotification(notification: AppNotification, t: TFn): LocalizedNotificationCopy {
  const meta = (notification.metadata ?? {}) as Record<string, string | undefined>;
  const typeLabel = t(humanizeType(notification.type));

  switch (notification.type) {
    case 'LEVEL_UP': {
      if (meta.level) {
        return {
          title: t('Level up!'),
          body: t(`Congratulations — you reached level ${meta.level}.`),
          typeLabel,
        };
      }
      break;
    }
    case 'POINT_EARNED': {
      if (meta.points) {
        const total = meta.totalPoints ?? meta.points;
        return {
          title: t('You earned points'),
          body: t(`You earned ${meta.points} points. Total: ${total}.`),
          typeLabel: t('Point earned'),
        };
      }
      break;
    }
    case 'SMART_RETURN_PROMPT':
      return {
        title: t('Are you driving today?'),
        body: t('Tell Parkio if you want a parking check before you return.'),
        typeLabel,
      };
    case 'SMART_RETURN_AVAILABLE':
      return {
        title: t('Parking may be available'),
        body: t('Parking near your saved home area may be available now.'),
        typeLabel,
      };
    case 'WARNING': {
      const warning = localizeWarning(meta, t);
      if (warning) return { ...warning, typeLabel };
      break;
    }
    case 'SYSTEM': {
      const system = localizeSystem(meta, t);
      if (system) return { ...system, typeLabel };
      break;
    }
    default:
      break;
  }

  return {
    title: t(notification.title),
    body: t(notification.body),
    typeLabel,
  };
}

function humanizeType(value: string): string {
  return value
    .toLowerCase()
    .split('_')
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join(' ');
}

function localizeWarning(meta: Record<string, string | undefined>, t: TFn): LocalizedNotificationCopy | null {
  switch (meta.messageKey) {
    case 'pointsDeducted':
      if (!meta.points) return null;
      return {
        title: t('Heads up'),
        body: t(`You lost ${meta.points} points (penalty).`),
      };
    case 'trustChanged': {
      if (
        meta.previousScore == null ||
        meta.newScore == null ||
        (meta.direction !== 'increased' && meta.direction !== 'decreased')
      ) {
        return null;
      }
      return {
        title: t('Heads up'),
        body: t(
          `Your trust score ${meta.direction} from ${meta.previousScore} to ${meta.newScore}.`,
        ),
      };
    }
    case 'spotRejectedIllegal':
      return {
        title: t('Heads up'),
        body: t('Your parking spot was rejected as illegal or risky.'),
      };
    case 'accountSuspended':
      return {
        title: t('Heads up'),
        body: t('Your account has been suspended by moderation.'),
      };
    case 'spotRejectedByModerator':
      return {
        title: t('Heads up'),
        body: t('Your parking spot was rejected by a moderator.'),
      };
    default:
      return null;
  }
}

function localizeSystem(meta: Record<string, string | undefined>, t: TFn): LocalizedNotificationCopy | null {
  switch (meta.messageKey) {
    case 'accountRestored':
      return {
        title: t('Update'),
        body: t('Your account has been restored.'),
      };
    case 'appealResolved': {
      if (meta.outcome !== 'accepted' && meta.outcome !== 'rejected') return null;
      return {
        title: t('Appeal update'),
        body: t(`Your appeal was ${meta.outcome}.`),
      };
    }
    case 'moderationCaseResolved':
      return {
        title: t('Update'),
        body: t('A moderation case about your account was resolved.'),
      };
    default:
      return null;
  }
}
