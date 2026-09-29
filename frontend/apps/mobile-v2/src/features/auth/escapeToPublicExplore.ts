import { clearPendingAuthGateIntent } from '@/features/auth/authGateIntents';

/**
 * Anonymous escape from auth → Public Explore.
 * Clears any pending AuthGate resume (e.g. contribute) so a later login
 * does not unexpectedly route to share.
 */
export function escapeToPublicExplore(router: { replace: (href: string) => void }): void {
  clearPendingAuthGateIntent();
  router.replace('/(public)/explore');
}
