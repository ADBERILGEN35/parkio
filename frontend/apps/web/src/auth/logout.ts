import { authApi } from '@/api';
import { showWarning } from '@/lib/toast';
import { useAuthStore } from './store';

/**
 * Revokes the cookie-backed refresh token server-side, then always clears local
 * auth state. Route guards redirect to /login once the session is gone.
 */
export async function performLogout(): Promise<void> {
  try {
    await authApi.logout();
  } catch {
    showWarning('Could not reach the server, but this browser was signed out.');
    // Local state is cleared regardless of backend logout outcome.
  }
  useAuthStore.getState().clearSession();
}
