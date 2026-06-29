import type { User } from '@parkio/types';
import { create } from 'zustand';

/**
 * In-memory auth state (zustand), mirroring the web app's model so behaviour is
 * consistent across platforms. Tokens themselves live in the keystore via
 * {@link ../services/tokenStorage}; this store holds the derived session state the
 * UI renders from.
 *
 * `sessionEpoch` guards against a late refresh resurrecting a session that was
 * torn down while the refresh network call was in flight (logout-during-refresh).
 */
export type AuthAccountStatus = 'ACTIVE' | 'SUSPENDED' | 'BANNED' | string;

interface AuthState {
  user: User | null;
  roles: string[];
  status: AuthAccountStatus | null;
  isAuthenticated: boolean;
  suspended: boolean;
  /** True until the first cold-start session-restore attempt settles. */
  bootstrapPending: boolean;
  sessionEpoch: number;

  setSession: (user: User) => void;
  setUser: (user: User) => void;
  clearSession: () => void;
  markSuspended: () => void;
  endBootstrap: () => void;
}

export const useAuthStore = create<AuthState>((set) => ({
  user: null,
  roles: [],
  status: null,
  isAuthenticated: false,
  suspended: false,
  bootstrapPending: true,
  sessionEpoch: 0,

  setSession: (user) =>
    set({
      user,
      roles: user.roles ?? [],
      status: user.status ?? 'ACTIVE',
      isAuthenticated: true,
      suspended: false,
    }),

  setUser: (user) => set({ user, roles: user.roles ?? [], status: user.status ?? 'ACTIVE' }),

  clearSession: () =>
    set((state) => ({
      user: null,
      roles: [],
      status: null,
      isAuthenticated: false,
      suspended: false,
      sessionEpoch: state.sessionEpoch + 1,
    })),

  markSuspended: () => set({ suspended: true }),

  endBootstrap: () => set({ bootstrapPending: false }),
}));
