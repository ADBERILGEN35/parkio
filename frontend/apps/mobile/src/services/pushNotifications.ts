import type * as NotificationsTypes from 'expo-notifications';
import { hasAdminRole, hasPrivilegedRole } from '@parkio/types';
import { Platform } from 'react-native';
import { notificationsApi } from '@/services/api';
import { recordError } from '@/services/crashReporting';
import { track } from '@/services/analytics';

/**
 * Push notifications — the single seam over `expo-notifications`.
 *
 * Supports device registration against the existing backend endpoint
 * (`POST /notifications/device-token`), token refresh, foreground display,
 * and tap → in-app route handling (see {@link addNotificationTapListener}).
 *
 * Honest platform limits: on Android, **remote** push does not work inside
 * Expo Go (SDK 53+); it requires a Development/EAS build with FCM
 * (`google-services.json`). Registration below fails soft in Expo Go and
 * logs why. Local notifications and tap routing work everywhere.
 */

/**
 * `expo-notifications` THROWS at import time inside Expo Go on Android (its
 * remote-push module was removed in SDK 53), and this file sits in the import
 * chain of the root layout — an eager import would keep the whole app from
 * booting in Expo Go. Load it lazily and treat "unavailable" as push-disabled.
 */
const Notifications: typeof NotificationsTypes | null = (() => {
  try {
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    return require('expo-notifications') as typeof NotificationsTypes;
  } catch (error) {
    if (__DEV__) {
      console.info('[push] expo-notifications unavailable in this runtime (Expo Go has no FCM):', error);
    }
    return null;
  }
})();

export interface PushRegistration {
  token: string;
  platform: 'ios' | 'android';
  /** Backend id of the registered token — used to deactivate on logout. */
  backendTokenId: string | null;
}

let registration: PushRegistration | null = null;
let tokenRefreshSubscription: NotificationsTypes.EventSubscription | null = null;

/** Show remote/local notifications while the app is foregrounded (banner, no sound).
 * Remote title/body are already localized by notification-service for the
 * recipient preferredLocale; data.messageKey remains available for re-render. */
export function configureForegroundHandling(): void {
  if (!Notifications) return;
  Notifications.setNotificationHandler({
    handleNotification: async () => ({
      shouldShowBanner: true,
      shouldShowList: true,
      shouldPlaySound: false,
      shouldSetBadge: false,
    }),
  });
  if (Platform.OS === 'android') {
    void Notifications.setNotificationChannelAsync('default', {
      name: 'Parkio',
      importance: Notifications.AndroidImportance.DEFAULT,
    });
  }
}

async function registerTokenWithBackend(token: string): Promise<string | null> {
  try {
    const result = await notificationsApi.registerDeviceToken(
      token,
      Platform.OS === 'ios' ? 'IOS' : 'ANDROID',
    );
    return result.id;
  } catch (error) {
    recordError(error, 'push:register-token');
    return null;
  }
}

export async function registerForPushNotifications(): Promise<PushRegistration | null> {
  if (!Notifications) return null;
  if (registration) return registration;
  try {
    const permission = await Notifications.requestPermissionsAsync();
    if (!permission.granted) return null;

    // Throws inside Expo Go on Android (no FCM) — fails soft with a log.
    const expoToken = await Notifications.getExpoPushTokenAsync();
    const backendTokenId = await registerTokenWithBackend(expoToken.data);
    registration = {
      token: expoToken.data,
      platform: Platform.OS === 'ios' ? 'ios' : 'android',
      backendTokenId,
    };

    // Token refresh: re-register the new token; the backend upserts by token.
    tokenRefreshSubscription?.remove();
    tokenRefreshSubscription = Notifications.addPushTokenListener((next) => {
      if (typeof next.data === 'string' && next.data !== registration?.token) {
        void registerTokenWithBackend(next.data).then((id) => {
          registration = { ...registration!, token: next.data as string, backendTokenId: id };
        });
      }
    });
    return registration;
  } catch (error) {
    if (__DEV__) {
      console.info('[push] registration unavailable in this runtime (Expo Go has no FCM):', error);
    } else {
      recordError(error, 'push:registration');
    }
    return null;
  }
}

/** Best-effort deactivation on logout so a signed-out device stops receiving pushes. */
export async function unregisterPushToken(): Promise<void> {
  const backendTokenId = registration?.backendTokenId;
  registration = null;
  tokenRefreshSubscription?.remove();
  tokenRefreshSubscription = null;
  if (!backendTokenId) return;
  try {
    await notificationsApi.deactivateDeviceToken(backendTokenId);
  } catch (error) {
    recordError(error, 'push:deactivate-token');
  }
}

/** Routes a notification may deep-link to via its `data.route` payload. */
const ALLOWED_ROUTES = [
  '/(main)/(tabs)/map',
  '/(main)/(tabs)/notifications',
  '/(main)/(tabs)/profile',
  '/(main)/map',
  '/(main)/upload',
  '/(main)/smart-return',
  '/(main)/my-spots',
  '/(main)/leaderboard',
  '/(main)/impact',
  '/(main)/reports',
  '/(main)/moderation',
  '/(main)/analytics',
  '/(main)/profile-edit',
] as const;

const MODERATOR_ROUTES = new Set<string>(['/(main)/moderation']);
const ADMIN_ONLY_ROUTES = new Set<string>(['/(main)/analytics']);

export type NotificationRoute = (typeof ALLOWED_ROUTES)[number];

/** Staff routes require the same roles as the gateway (moderation vs platform analytics). */
export function guardNotificationRoute(route: NotificationRoute, roles: string[]): NotificationRoute {
  if (MODERATOR_ROUTES.has(route) && !hasPrivilegedRole(roles)) {
    return '/(main)/(tabs)/notifications';
  }
  if (ADMIN_ONLY_ROUTES.has(route) && !hasAdminRole(roles)) {
    return '/(main)/(tabs)/notifications';
  }
  return route;
}

function routeFromResponse(response: NotificationsTypes.NotificationResponse): NotificationRoute | null {
  const data = response.notification.request.content.data as { route?: unknown } | undefined;
  const route = typeof data?.route === 'string' ? data.route : null;
  return (ALLOWED_ROUTES as readonly string[]).includes(route ?? '')
    ? (route as NotificationRoute)
    : null;
}

/**
 * Notification tap handling — fires for taps while running AND for the tap that
 * cold-started the app (initial response). The allow-list above means a
 * malformed payload can never navigate somewhere unexpected; taps without a
 * route land on the Notifications tab.
 */
export function addNotificationTapListener(onRoute: (route: NotificationRoute) => void): () => void {
  if (!Notifications) return () => {};
  const handle = (response: NotificationsTypes.NotificationResponse) => {
    track('notification_opened');
    onRoute(routeFromResponse(response) ?? '/(main)/(tabs)/notifications');
  };

  void Notifications.getLastNotificationResponseAsync().then((initial) => {
    if (initial) handle(initial);
  });
  const subscription = Notifications.addNotificationResponseReceivedListener(handle);
  return () => subscription.remove();
}
