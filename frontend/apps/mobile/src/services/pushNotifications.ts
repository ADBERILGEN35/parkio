/**
 * Push notifications — PLACEHOLDER (M0).
 *
 * Push registration, permission prompts, Expo push tokens and notification
 * handlers are intentionally NOT implemented this sprint. `expo-notifications` is
 * installed and the seam is defined here so a later sprint can fill it in without
 * touching the rest of the app. Do not call into `expo-notifications` elsewhere —
 * route all of it through this module.
 */
export interface PushRegistration {
  token: string;
  platform: 'ios' | 'android';
}

export async function registerForPushNotifications(): Promise<PushRegistration | null> {
  // M2+: request permissions, get the Expo push token, and register it with the
  // notification-service. Returns null today (feature not enabled yet).
  if (__DEV__) {
    console.info('[push] registerForPushNotifications is a placeholder (M0).');
  }
  return null;
}
