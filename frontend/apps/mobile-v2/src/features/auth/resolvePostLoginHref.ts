import type { Href } from 'expo-router';
import type { AuthGateResumeMeta } from '@/features/auth/authGateIntents';

/**
 * Post-login destination from a pending AuthGate intent.
 * Contribute → share wizard; facility-detail / google-maps → authenticated detail (public id);
 * other intents → map.
 * Google Maps URL is opened separately from validated lat/lng (never from stored URL).
 *
 * Facility detail uses pathname+params so Expo Router populates `useLocalSearchParams().id`.
 */
export function resolvePostLoginHref(pending: AuthGateResumeMeta | null): Href {
  if (pending?.intent === 'contribute') {
    return '/(main)/share';
  }
  if (pending?.intent === 'facility-detail' || pending?.intent === 'google-maps') {
    const facilityId = pending.facilityId?.trim();
    if (facilityId) {
      // Public municipal id only — already visible anonymously; no private payload in params.
      return {
        pathname: '/(main)/facilities/[id]',
        params: { id: facilityId },
      };
    }
  }
  return '/(main)/(tabs)/map';
}
