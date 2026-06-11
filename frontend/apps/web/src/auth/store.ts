import type { User } from '@parkio/types';
import { create } from 'zustand';
import { webTokenStorage } from './token-storage';

export type AuthAccountStatus = 'ACTIVE' | 'SUSPENDED' | 'BANNED' | string;

interface AuthState {
  accessToken: string | null;
  refreshToken: string | null;
  user: User | null;
  roles: string[];
  status: AuthAccountStatus | null;
  isAuthenticated: boolean;
  /** True after any API call returned 403 ACCOUNT_NOT_ACTIVE. */
  suspended: boolean;
  setSession: (accessToken: string, refreshToken: string, user: User) => void;
  clearSession: () => void;
  setUser: (user: User) => void;
  markSuspended: () => void;
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
  accessToken: webTokenStorage.getAccessToken(),
  refreshToken: webTokenStorage.getRefreshToken(),
  user: null,
  roles: [],
  status: null,
  isAuthenticated: Boolean(webTokenStorage.getAccessToken()),
  suspended: false,

  setSession(accessToken, refreshToken, user) {
    webTokenStorage.setTokens({ accessToken, refreshToken });
    set({ refreshToken, suspended: false, ...deriveAuth(accessToken, user) });
  },

  clearSession() {
    webTokenStorage.clearTokens();
    set({
      accessToken: null,
      refreshToken: null,
      user: null,
      roles: [],
      status: null,
      isAuthenticated: false,
      suspended: false,
    });
  },

  setUser(user) {
    set((state) => deriveAuth(state.accessToken, user));
  },

  markSuspended() {
    set({ suspended: true });
  },
}));
