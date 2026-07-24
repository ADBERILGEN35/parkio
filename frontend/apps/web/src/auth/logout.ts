import { showWarning } from '@/lib/toast';
import type { AuthSession } from './session';

/**
 * Revokes the cookie-backed refresh token server-side, then always clears local
 * auth state and tells other tabs to sign out too. RoutePolicyBoundary redirects
 * to /login once the session is gone.
 */
export async function performLogout(authSession: AuthSession): Promise<void> {
  const result = await authSession.logout();
  if (!result.backendSucceeded) {
    showWarning('Could not reach the server, but this browser was signed out.');
  }
}
