import { hasAdminRole, hasPrivilegedRole, type NotificationType } from '@parkio/types';
import {
  ROUTE_IDS,
  ROUTE_MANIFEST,
  buildRoutePath,
  findRouteManifestEntryByPath,
  getRoutePath,
  isRedirectEligiblePath,
  type RouteManifestEntry,
} from '@/routing/route-manifest';

const NOTIFICATIONS_PATH = buildRoutePath(ROUTE_IDS.NOTIFICATIONS);

function isNotificationDestination(entry: RouteManifestEntry): boolean {
  return Boolean(
    entry.navigation ||
      entry.id === ROUTE_IDS.SPOT_DETAIL ||
      entry.redirect ||
      ROUTE_MANIFEST.some(
        (candidate) => candidate.redirect?.targetId === entry.id,
      ),
  );
}

function staticNotificationDestinations(): readonly string[] {
  return Object.freeze(
    ROUTE_MANIFEST.filter(
      (entry) =>
        entry.redirectEligible &&
        entry.parameters.length === 0 &&
        isNotificationDestination(entry),
    ).map((entry) => getRoutePath(entry.id)),
  );
}

/** Manifest-derived Web paths a notification may navigate to. */
export const ALLOWED_NOTIFICATION_PATHS = staticNotificationDestinations();
export type AllowedNotificationPath = (typeof ALLOWED_NOTIFICATION_PATHS)[number];

function normalizePath(deeplink: string): string {
  const trimmed = deeplink.trim();
  if (!trimmed.startsWith('/')) {
    return NOTIFICATIONS_PATH;
  }
  const [path] = trimmed.split('?', 2);
  return path || NOTIFICATIONS_PATH;
}

function notificationRoute(path: string): RouteManifestEntry | null {
  if (path.includes('?') || path.includes('#')) {
    return null;
  }
  const entry = findRouteManifestEntryByPath(path);
  if (
    !entry ||
    !isRedirectEligiblePath(path) ||
    !isNotificationDestination(entry)
  ) {
    return null;
  }
  return entry;
}

export function isAllowedNotificationPath(path: string): boolean {
  return notificationRoute(path) !== null;
}

export function isStaffOnlyNotificationPath(path: string): boolean {
  const role = notificationRoute(path)?.role;
  return role === 'privileged' || role === 'admin';
}

export function isAdminOnlyNotificationPath(path: string): boolean {
  return notificationRoute(path)?.role === 'admin';
}

/** Resolves a safe in-app path for a notification tap. */
export function resolveNotificationNavigation(
  deeplink: string | undefined,
  type: NotificationType,
  roles: string[],
): string {
  if (deeplink) {
    const path = normalizePath(deeplink);
    const route = notificationRoute(path);
    if (!route) {
      return NOTIFICATIONS_PATH;
    }
    if (route.role === 'privileged' && !hasPrivilegedRole(roles)) {
      return NOTIFICATIONS_PATH;
    }
    if (route.role === 'admin' && !hasAdminRole(roles)) {
      return NOTIFICATIONS_PATH;
    }
    return deeplink.startsWith('/') ? deeplink : NOTIFICATIONS_PATH;
  }

  switch (type) {
    case 'SMART_RETURN_PROMPT':
      return `${buildRoutePath(ROUTE_IDS.PROFILE)}?section=smart-return`;
    case 'SMART_RETURN_AVAILABLE':
      return `${buildRoutePath(ROUTE_IDS.MAP)}?smartReturn=1`;
    case 'POINT_EARNED':
    case 'LEVEL_UP':
      return buildRoutePath(ROUTE_IDS.GAMIFICATION);
    case 'WARNING':
      return buildRoutePath(ROUTE_IDS.REPORTS);
    default:
      return NOTIFICATIONS_PATH;
  }
}
