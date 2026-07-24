import { useStore } from 'zustand';
import { useAppRuntime } from '@/app/AppRuntimeContext';
import type { AuthState, AuthStore } from './auth-store';

export type {
  AuthAccountStatus,
  AuthIdentity,
  AuthIdentityChange,
  AuthLifecycle,
  AuthRestriction,
  AuthState,
  AuthStore,
} from './auth-store';

/** Returns the authentication store owned by the mounted application runtime. */
export function useAuthStoreApi(): AuthStore {
  return useAppRuntime().authStore;
}

/** Selects authentication state from the mounted application runtime. */
export function useAuthStore<T>(selector: (state: AuthState) => T): T {
  return useStore(useAuthStoreApi(), selector);
}
