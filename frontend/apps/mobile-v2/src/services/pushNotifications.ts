import type * as NotificationsTypes from 'expo-notifications';
import Constants, { ExecutionEnvironment } from 'expo-constants';
import { hasAdminRole, hasPrivilegedRole } from '@parkio/types';
import { Platform } from 'react-native';
import { notificationsApi } from '@/services/api';

/**
 * Push notifications — the single seam over `expo-notifications`.
 *
 * Registers the Expo push token against `POST /notifications/device-token`,
 * handles token refresh, foreground display and tap → in-app route handling.
 *
 * Honest platform limits: on Android, remote push does not work inside Expo Go
 * (SDK 53+); it needs a development/EAS build with FCM. Registration fails
 * soft there. Local notifications and tap routing work everywhere.
 */

/**
 * `expo-notifications` THROWS at import time inside Expo Go on Android (its
 * remote-push module was removed in SDK 53) and this file is in the root
 * layout's import chain — an eager import would keep the app from booting.
 * A try/catch is not enough: Metro reports module-factory throws to LogBox as
 * an uncaught error even when the require site catches them, so in Expo Go on
 * Android the require must never run at all.
 */
const pushRuntimeAvailable = !(
  Platform.OS === 'android' &&
  Constants.executionEnvironment === ExecutionEnvironment.StoreClient
);

const Notifications: typeof NotificationsTypes | null = (() => {
  if (!pushRuntimeAvailable) {
    if (__DEV__) {
      console.info('[push] expo-notifications skipped: Expo Go on Android has no remote push.');
    }
    return null;
  }
  try {
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    return require('expo-notifications') as typeof NotificationsTypes;
  } catch (error) {
    if (__DEV__) {
      console.info('[push] expo-notifications unavailable in this runtime:', error);
    }
    return null;
  }
})();

/** Ask for the OS notification permission (onboarding priming). Fails soft. */
export async function requestPushPermissions(): Promise<void> {
  if (!Notifications) return;
  try {
    await Notifications.requestPermissionsAsync();
  } catch {
    // Push unavailable in this runtime — priming already shown, fail soft.
  }
}

export interface PushRegistration {
  token: string;
  platform: 'ios' | 'android';
  /** Backend id of the registered token — used to deactivate on logout. */
  backendTokenId: string | null;
}

let registration: PushRegistration | null = null;
let tokenRefreshSubscription: NotificationsTypes.EventSubscription | null = null;

/** Show notifications while foregrounded (banner, no sound). */
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
    console.warn('[push] token registration failed', error);
    return null;
  }
}

/**
 * Register for remote push. Does NOT prompt for permission — that happens in
 * onboarding priming; here we only proceed if permission is already granted
 * (or ask once when it is still undetermined on iOS after priming said yes).
 */
export async function registerForPushNotifications(): Promise<PushRegistration | null> {
  if (!Notifications) return null;
  if (registration) return registration;
  try {
    const current = await Notifications.getPermissionsAsync();
    const granted = current.granted
      ? true
      : current.canAskAgain
        ? (await Notifications.requestPermissionsAsync()).granted
        : false;
    if (!granted) return null;

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
      console.info('[push] registration unavailable in this runtime:', error);
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
    console.warn('[push] token deactivation failed', error);
  }
}

/**
 * Backend `data.route` payloads were minted against the v1 app's file layout.
 * Map them onto v2 routes so existing notification templates keep deep-linking.
 * Unknown/malformed routes land on the notifications screen.
 */
const ROUTE_MAP: Record<string, string> = {
  '/(main)/(tabs)/map': '/(main)/(tabs)/map',
  '/(main)/map': '/(main)/(tabs)/map',
  '/(main)/(tabs)/notifications': '/(main)/notifications',
  '/(main)/(tabs)/profile': '/(main)/(tabs)/profile',
  '/(main)/upload': '/(main)/share',
  '/(main)/smart-return': '/(main)/smart-return',
  '/(main)/my-spots': '/(main)/(tabs)/my-spots',
  '/(main)/leaderboard': '/(main)/(tabs)/leaderboard',
  '/(main)/impact': '/(main)/impact',
  '/(main)/reports': '/(main)/reports',
  '/(main)/moderation': '/(main)/moderation',
  '/(main)/analytics': '/(main)/moderation/analytics',
  '/(main)/profile-edit': '/(main)/profile/edit',
};

const MODERATOR_ROUTES = new Set(['/(main)/moderation']);
const ADMIN_ONLY_ROUTES = new Set(['/(main)/moderation/analytics']);

const FALLBACK_ROUTE = '/(main)/notifications';

/** Staff routes require the same roles the gateway enforces. */
export function guardNotificationRoute(route: string, roles: string[]): string {
  if (MODERATOR_ROUTES.has(route) && !hasPrivilegedRole(roles)) {
    return FALLBACK_ROUTE;
  }
  if (ADMIN_ONLY_ROUTES.has(route) && !hasAdminRole(roles)) {
    return FALLBACK_ROUTE;
  }
  return route;
}

function routeFromResponse(response: NotificationsTypes.NotificationResponse): string {
  const data = response.notification.request.content.data as { route?: unknown } | undefined;
  const raw = typeof data?.route === 'string' ? data.route : '';
  return ROUTE_MAP[raw] ?? FALLBACK_ROUTE;
}

/**
 * Notification tap handling — fires for taps while running AND for the tap
 * that cold-started the app. The allow-list mapping means a malformed payload
 * can never navigate somewhere unexpected.
 */
export function addNotificationTapListener(onRoute: (route: string) => void): () => void {
  if (!Notifications) return () => {};
  const handle = (response: NotificationsTypes.NotificationResponse) => {
    onRoute(routeFromResponse(response));
  };

  void Notifications.getLastNotificationResponseAsync().then((initial) => {
    if (initial) handle(initial);
  });
  const subscription = Notifications.addNotificationResponseReceivedListener(handle);
  return () => subscription.remove();
}
