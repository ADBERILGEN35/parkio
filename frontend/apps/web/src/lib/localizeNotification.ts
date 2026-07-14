import type { AppNotification } from '@parkio/types';
import i18n from 'i18next';

export interface LocalizedNotificationCopy {
  title: string;
  body: string;
}

export function localizeNotification(notification: AppNotification): LocalizedNotificationCopy {
  const meta = notification.metadata ?? {};
  const t = i18n.t.bind(i18n);

  switch (notification.type) {
    case 'LEVEL_UP': {
      if (meta.level) {
        return {
          title: t('parking:notifications.content.levelUp.title'),
          body: t('parking:notifications.content.levelUp.body', { level: meta.level }),
        };
      }
      break;
    }
    case 'POINT_EARNED': {
      if (meta.points) {
        return {
          title: t('parking:notifications.content.pointEarned.title'),
          body: t('parking:notifications.content.pointEarned.body', {
            points: meta.points,
            totalPoints: meta.totalPoints ?? meta.points,
          }),
        };
      }
      break;
    }
    case 'SMART_RETURN_PROMPT':
      return {
        title: t('parking:notifications.content.smartReturnPrompt.title'),
        body: t('parking:notifications.content.smartReturnPrompt.body'),
      };
    case 'SMART_RETURN_AVAILABLE':
      return {
        title: t('parking:notifications.content.smartReturnAvailable.title'),
        body: t('parking:notifications.content.smartReturnAvailable.body'),
      };
    case 'WARNING': {
      const warning = localizeWarning(meta, t);
      if (warning) return warning;
      break;
    }
    case 'SYSTEM': {
      const system = localizeSystem(meta, t);
      if (system) return system;
      break;
    }
    default:
      break;
  }

  return { title: notification.title, body: notification.body };
}

function localizeWarning(
  meta: Record<string, string>,
  t: typeof i18n.t,
): LocalizedNotificationCopy | null {
  switch (meta.messageKey) {
    case 'pointsDeducted':
      if (!meta.points) return null;
      return {
        title: t('parking:notifications.content.warnings.pointsDeducted.title'),
        body: t('parking:notifications.content.warnings.pointsDeducted.body', { points: meta.points }),
      };
    case 'trustChanged':
      if (
        meta.previousScore == null ||
        meta.newScore == null ||
        (meta.direction !== 'increased' && meta.direction !== 'decreased')
      ) {
        return null;
      }
      return {
        title: t('parking:notifications.content.warnings.trustChanged.title'),
        body: t('parking:notifications.content.warnings.trustChanged.body', {
          previousScore: meta.previousScore,
          newScore: meta.newScore,
          directionLabel: t(
            'parking:notifications.content.warnings.trustChanged.direction.' + meta.direction,
          ),
        }),
      };
    case 'spotRejectedIllegal':
      return {
        title: t('parking:notifications.content.warnings.spotRejectedIllegal.title'),
        body: t('parking:notifications.content.warnings.spotRejectedIllegal.body'),
      };
    case 'accountSuspended':
      return {
        title: t('parking:notifications.content.warnings.accountSuspended.title'),
        body: t('parking:notifications.content.warnings.accountSuspended.body'),
      };
    case 'spotRejectedByModerator':
      return {
        title: t('parking:notifications.content.warnings.spotRejectedByModerator.title'),
        body: t('parking:notifications.content.warnings.spotRejectedByModerator.body'),
      };
    default:
      return null;
  }
}

function localizeSystem(
  meta: Record<string, string>,
  t: typeof i18n.t,
): LocalizedNotificationCopy | null {
  switch (meta.messageKey) {
    case 'accountRestored':
      return {
        title: t('parking:notifications.content.system.accountRestored.title'),
        body: t('parking:notifications.content.system.accountRestored.body'),
      };
    case 'appealResolved': {
      if (meta.outcome !== 'accepted' && meta.outcome !== 'rejected') return null;
      return {
        title: t('parking:notifications.content.system.appealResolved.title'),
        body: t('parking:notifications.content.system.appealResolved.body', {
          outcomeLabel: t(
            'parking:notifications.content.system.appealResolved.outcome.' + meta.outcome,
          ),
        }),
      };
    }
    case 'moderationCaseResolved':
      return {
        title: t('parking:notifications.content.system.moderationCaseResolved.title'),
        body: t('parking:notifications.content.system.moderationCaseResolved.body'),
      };
    default:
      return null;
  }
}
