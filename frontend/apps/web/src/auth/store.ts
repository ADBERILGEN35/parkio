import type { User } from '@parkio/types';
import { create } from 'zustand';
import { clearPendingProfile } from './pendingProfile';
import { webTokenStorage } from './token-storage';

export type AuthAccountStatus = 'ACTIVE' | 'SUSPENDED' | 'BANNED' | string;

interface AuthState {
  accessToken: string | null;
  user: User | null;
  roles: string[];
  status: AuthAccountStatus | null;
  isAuthenticated: boolean;
  /** True after any API call returned 403 ACCOUNT_NOT_ACTIVE. */
  suspended: boolean;
  /**
   * True only during the post-register grace window while user-service
   * provisions the profile asynchronously. While set, a 403 ACCOUNT_NOT_ACTIVE
   * is treated as "still provisioning" instead of a real suspension, so
   * `markSuspended()` is a no-op. The AccountPreparingPage owns this window.
   */
  provisioning: boolean;
  setSession: (accessToken: string, user: User) => void;
  clearSession: () => void;
  setUser: (user: User) => void;
  markSuspended: () => void;
  beginProvisioning: () => void;
  endProvisioning: () => void;
}

function deriveAuth(accessToken: string | null, user: User | null) {
  return {
    accessToken,
    user,
    roles: user?.roles ?? [],
    status: (user?.status as AuthAccountStatus) ?? null,
    isAuthenticated: Boolean(accessToken && user),
  };
}

export const useAuthStore = create<AuthState>((set) => ({
  accessToken: null,
  user: null,
  roles: [],
  status: null,
  isAuthenticated: false,
  suspended: false,
  provisioning: false,

  setSession(accessToken, user) {
    webTokenStorage.setTokens({ accessToken });
    set({ suspended: false, provisioning: false, ...deriveAuth(accessToken, user) });
  },

  clearSession() {
    webTokenStorage.clearTokens();
    clearPendingProfile();
    set({
      accessToken: null,
      user: null,
      roles: [],
      status: null,
      isAuthenticated: false,
      suspended: false,
      provisioning: false,
    });
  },

  setUser(user) {
    set((state) => deriveAuth(state.accessToken, user));
  },

  markSuspended() {
    // During the post-register provisioning grace window a 403 ACCOUNT_NOT_ACTIVE
    // is expected (the profile/status is still being created), so it must not flip
    // the global suspended flag. Outside that window this still marks suspension,
    // so genuinely suspended accounts (login/session) keep seeing the suspended page.
    set((state) => (state.provisioning ? state : { suspended: true }));
  },

  beginProvisioning() {
    set({ provisioning: true, suspended: false });
  },

  endProvisioning() {
    set({ provisioning: false });
  },
}));
