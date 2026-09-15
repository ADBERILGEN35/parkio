import type { AuthGateResumeMeta } from '@/features/auth/authGateIntents';

/**
 * Post-login destination from a pending AuthGate intent.
 * Contribute → share wizard; facility-detail / google-maps → authenticated detail (public id);
 * other intents → map.
 * Google Maps URL is opened separately from validated lat/lng (never from stored URL).
 */
export function resolvePostLoginHref(pending: AuthGateResumeMeta | null): string {
  if (pending?.intent === 'contribute') {
    return '/(main)/share';
  }
  if (pending?.intent === 'facility-detail' || pending?.intent === 'google-maps') {
    const facilityId = pending.facilityId?.trim();
    if (facilityId) {
      // Public municipal id only — already visible anonymously; no private payload in params.
      return `/(main)/facilities/${encodeURIComponent(facilityId)}`;
    }
  }
  return '/(main)/(tabs)/map';
}
