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
  /**
   * True until the first post-reload refresh attempt (AuthBootstrap) settles.
   * While set, route guards show a loader instead of bouncing to /login, so a
   * valid session being restored from the HttpOnly cookie does not flash login.
   */
  bootstrapPending: boolean;
  /**
   * Monotonic counter bumped on every session teardown (clearSession/logout).
   * An in-flight refresh captures it before its network call and refuses to
   * apply a late success if it changed — so a logout during refresh cannot
   * resurrect the session.
   */
  sessionEpoch: number;
  setSession: (accessToken: string, user: User) => void;
  clearSession: () => void;
  setUser: (user: User) => void;
  markSuspended: () => void;
  beginProvisioning: () => void;
  endProvisioning: () => void;
  endBootstrap: () => void;
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
  bootstrapPending: true,
  sessionEpoch: 0,

  setSession(accessToken, user) {
    webTokenStorage.setTokens({ accessToken });
    set({ suspended: false, provisioning: false, bootstrapPending: false, ...deriveAuth(accessToken, user) });
  },

  clearSession() {
    webTokenStorage.clearTokens();
    clearPendingProfile();
    set((state) => ({
      accessToken: null,
      user: null,
      roles: [],
      status: null,
      isAuthenticated: false,
      suspended: false,
      provisioning: false,
      bootstrapPending: false,
      sessionEpoch: state.sessionEpoch + 1,
    }));
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

  endBootstrap() {
    set({ bootstrapPending: false });
  },
}));
