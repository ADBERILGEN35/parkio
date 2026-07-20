import { create } from 'zustand';
import type { User } from '@parkio/types';

/**
 * In-memory session state. Persistence lives in `secureStore`/`tokenStorage`;
 * this store is the single UI-facing source of truth for "who is signed in".
 */
export type AuthStatus = 'bootstrapping' | 'authenticated' | 'anonymous' | 'suspended';

export const STAFF_ROLES = ['MODERATOR', 'ADMIN', 'SUPER_ADMIN'] as const;
export const ADMIN_ROLES = ['ADMIN', 'SUPER_ADMIN'] as const;

interface AuthState {
  status: AuthStatus;
  user: User | null;
  /**
   * Monotonic counter bumped on every sign-out. An async flow (e.g. the
   * single-flight refresh) captures the epoch when it starts and discards its
   * result if the epoch moved — a late success cannot resurrect a dead session.
   */
  sessionEpoch: number;
  setSession: (user: User) => void;
  clearSession: () => void;
  markSuspended: () => void;
  finishBootstrap: () => void;
}

export const useAuthStore = create<AuthState>((set) => ({
  status: 'bootstrapping',
  user: null,
  sessionEpoch: 0,
  setSession: (user) => set({ user, status: 'authenticated' }),
  clearSession: () =>
    set((state) => ({ user: null, status: 'anonymous', sessionEpoch: state.sessionEpoch + 1 })),
  markSuspended: () => set({ status: 'suspended' }),
  finishBootstrap: () =>
    set((state) => (state.status === 'bootstrapping' ? { status: 'anonymous' } : {})),
}));

export function rolesOf(user: User | null): string[] {
  return user?.roles ?? [];
}

export function isStaff(user: User | null): boolean {
  const roles = rolesOf(user);
  return STAFF_ROLES.some((role) => roles.includes(role));
}

export function isAdmin(user: User | null): boolean {
  const roles = rolesOf(user);
  return ADMIN_ROLES.some((role) => roles.includes(role));
}
