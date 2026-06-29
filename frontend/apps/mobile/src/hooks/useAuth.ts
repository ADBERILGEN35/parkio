import { useAuthStore } from '@/state/authStore';
import { signIn, signOut, signOutAll, signUp } from '@/services/auth';

/**
 * Convenience facade combining auth session state with the auth operations, so
 * screens have a single ergonomic hook (`const { user, login, logout } = useAuth()`).
 */
export function useAuth() {
  const user = useAuthStore((s) => s.user);
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);
  const suspended = useAuthStore((s) => s.suspended);
  const bootstrapPending = useAuthStore((s) => s.bootstrapPending);
  const roles = useAuthStore((s) => s.roles);

  return {
    user,
    roles,
    isAuthenticated,
    suspended,
    bootstrapPending,
    login: signIn,
    register: signUp,
    logout: signOut,
    logoutAll: signOutAll,
  };
}
