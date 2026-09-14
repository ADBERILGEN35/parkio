import type { AuthGateResumeMeta } from '@/features/auth/authGateIntents';

/**
 * Post-login destination from a pending AuthGate intent.
 * Contribute → existing authenticated share wizard; other intents → map.
 */
export function resolvePostLoginHref(pending: AuthGateResumeMeta | null): string {
  if (pending?.intent === 'contribute') {
    return '/(main)/share';
  }
  return '/(main)/(tabs)/map';
}
