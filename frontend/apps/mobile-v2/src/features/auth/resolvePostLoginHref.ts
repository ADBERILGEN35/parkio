import type { AuthGateResumeMeta } from '@/features/auth/authGateIntents';

/**
 * Post-login destination from a pending AuthGate intent.
 * Contribute → share wizard; facility-detail → authenticated detail (public id only);
 * other intents → map.
 */
export function resolvePostLoginHref(pending: AuthGateResumeMeta | null): string {
  if (pending?.intent === 'contribute') {
    return '/(main)/share';
  }
  if (pending?.intent === 'facility-detail') {
    const facilityId = pending.facilityId?.trim();
    if (facilityId) {
      // Public municipal id only — already visible anonymously; no private payload in params.
      return `/(main)/facilities/${encodeURIComponent(facilityId)}`;
    }
  }
  return '/(main)/(tabs)/map';
}
