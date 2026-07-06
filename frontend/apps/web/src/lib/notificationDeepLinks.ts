import { hasAdminRole, hasPrivilegedRole, type NotificationType } from '@parkio/types';

/** Web paths a notification may navigate to. Unknown paths fail closed to /notifications. */
export const ALLOWED_NOTIFICATION_PATHS = [
  '/map',
  '/notifications',
  '/profile',
  '/reports',
  '/my-spots',
  '/upload',
  '/gamification',
  '/leaderboard',
  '/moderation',
  '/analytics',
] as const;

const MODERATOR_ONLY_PATHS = new Set<string>(['/moderation']);
const ADMIN_ONLY_PATHS = new Set<string>(['/analytics']);

const SPOT_PATH = /^\/spots\/[0-9a-f-]{36}$/i;

export type AllowedNotificationPath = (typeof ALLOWED_NOTIFICATION_PATHS)[number];

function normalizePath(deeplink: string): string {
  const trimmed = deeplink.trim();
  if (!trimmed.startsWith('/')) {
    return '/notifications';
  }
  const [path] = trimmed.split('?', 2);
  return path || '/notifications';
}

export function isAllowedNotificationPath(path: string): boolean {
  if ((ALLOWED_NOTIFICATION_PATHS as readonly string[]).includes(path)) {
    return true;
  }
  return SPOT_PATH.test(path);
}

export function isStaffOnlyNotificationPath(path: string): boolean {
  return MODERATOR_ONLY_PATHS.has(path) || ADMIN_ONLY_PATHS.has(path);
}

export function isAdminOnlyNotificationPath(path: string): boolean {
  return ADMIN_ONLY_PATHS.has(path);
}

/** Resolves a safe in-app path for a notification tap. */
export function resolveNotificationNavigation(
  deeplink: string | undefined,
  type: NotificationType,
  roles: string[],
): string {
  if (deeplink) {
    const path = normalizePath(deeplink);
    if (!isAllowedNotificationPath(path)) {
      return '/notifications';
    }
    if (MODERATOR_ONLY_PATHS.has(path) && !hasPrivilegedRole(roles)) {
      return '/notifications';
    }
    if (ADMIN_ONLY_PATHS.has(path) && !hasAdminRole(roles)) {
      return '/notifications';
    }
    return deeplink.startsWith('/') ? deeplink : '/notifications';
  }

  switch (type) {
    case 'SMART_RETURN_PROMPT':
      return '/profile?section=smart-return';
    case 'SMART_RETURN_AVAILABLE':
      return '/map?smartReturn=1';
    case 'POINT_EARNED':
    case 'LEVEL_UP':
      return '/gamification';
    case 'WARNING':
      return '/reports';
    default:
      return '/notifications';
  }
}