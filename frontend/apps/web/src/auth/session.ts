import {
  AccountNotActiveError,
  AccountNotVerifiedError,
  ForbiddenError,
  refreshSession,
  type AuthApi,
} from '@parkio/api-client';
import type { AuthLifecycle, AuthStore } from './auth-store';
import {
  createCrossTabSessionSync,
  type CrossTabSessionSync,
} from './crossTabSync';
import { getPendingProfile } from './pendingProfile';

export interface LogoutResult {
  readonly backendSucceeded: boolean;
}

export type AuthBootstrapEntry = 'public' | 'protected';

export interface AuthSession {
  bootstrap(entry: AuthBootstrapEntry): Promise<AuthLifecycle>;
  refreshThroughSdk(): Promise<string | null>;
  handleSdkUnauthorized(): void;
  handleAccountNotActive(): void;
  destroyLocalSession(): boolean;
  logout(): Promise<LogoutResult>;
  dispose(): void;
}

interface AuthSessionOptions {
  readonly authApi: Pick<AuthApi, 'logout' | 'refresh'>;
  readonly authStore: AuthStore;
  readonly crossTabSync?: CrossTabSessionSync;
}

/**
 * Owns the authentication lifecycle for one Web application runtime. HTTP refresh
 * execution and single-flight coordination remain exclusively in the SDK.
 */
export function createAuthSession({
  authApi,
  authStore,
  crossTabSync = createCrossTabSessionSync(),
}: AuthSessionOptions): AuthSession {
  let bootstrapPromise: Promise<AuthLifecycle> | null = null;
  let logoutPromise: Promise<LogoutResult> | null = null;
  let preservedRefreshEpoch: number | null = null;
  let lastBroadcastEpoch: number | null = null;

  const preserveCurrentSessionFromSdkTeardown = () => {
    preservedRefreshEpoch = authStore.getState().sessionEpoch;
  };

  const teardown = (broadcast: boolean): boolean => {
    const cleared = authStore.getState().clearSession();
    const currentEpoch = authStore.getState().sessionEpoch;
    if (broadcast && (cleared || lastBroadcastEpoch !== currentEpoch)) {
      crossTabSync.broadcastSessionDestruction();
      lastBroadcastEpoch = currentEpoch;
    }
    return cleared;
  };

  const unsubscribeRemoteSessionDestruction = crossTabSync.subscribe(() => {
    // Remote invalidation is terminal locally but is never echoed to other tabs.
    teardown(false);
  });

  const session: AuthSession = {
    bootstrap(entry) {
      const state = authStore.getState();
      if (!state.bootstrapPending && state.lifecycle !== 'bootstrapping') {
        return Promise.resolve(state.lifecycle);
      }

      if (entry === 'public') {
        authStore.getState().endBootstrap();
        return Promise.resolve(authStore.getState().lifecycle);
      }

      bootstrapPromise ??= refreshSession()
        .catch(() => null)
        .then(() => {
          authStore.getState().endBootstrap();
          return authStore.getState().lifecycle;
        })
        .finally(() => {
          bootstrapPromise = null;
        });
      return bootstrapPromise;
    },

    async refreshThroughSdk() {
      const epochAtStart = authStore.getState().sessionEpoch;
      preservedRefreshEpoch = null;

      try {
        const result = await authApi.refresh();
        if (!result.accessToken) {
          throw new Error('Refresh response did not include an access token.');
        }

        const restored = authStore
          .getState()
          .restoreSession(epochAtStart, result.accessToken, result.user);
        if (!restored) {
          preserveCurrentSessionFromSdkTeardown();
          return null;
        }
        if (getPendingProfile()) {
          authStore.getState().beginProvisioning();
        }
        return result.accessToken;
      } catch (error) {
        if (authStore.getState().sessionEpoch !== epochAtStart) {
          preserveCurrentSessionFromSdkTeardown();
          return null;
        }

        if (error instanceof AccountNotActiveError) {
          authStore.getState().markAccountRestricted('ACCOUNT_NOT_ACTIVE');
          preserveCurrentSessionFromSdkTeardown();
          return null;
        }
        if (error instanceof AccountNotVerifiedError) {
          authStore.getState().markAccountRestricted('ACCOUNT_NOT_VERIFIED');
          preserveCurrentSessionFromSdkTeardown();
          return null;
        }
        if (error instanceof ForbiddenError) {
          // Authorization failure does not invalidate an otherwise valid session.
          preserveCurrentSessionFromSdkTeardown();
          return null;
        }

        teardown(true);
        return null;
      }
    },

    handleSdkUnauthorized() {
      if (
        preservedRefreshEpoch !== null &&
        authStore.getState().sessionEpoch >= preservedRefreshEpoch
      ) {
        return;
      }
      teardown(true);
    },

    handleAccountNotActive() {
      authStore.getState().markAccountRestricted('ACCOUNT_NOT_ACTIVE');
    },

    destroyLocalSession() {
      return teardown(true);
    },

    logout() {
      const state = authStore.getState();
      if (
        state.lifecycle === 'anonymous' &&
        !state.bootstrapPending &&
        !state.accessToken &&
        !state.user
      ) {
        return Promise.resolve(Object.freeze({ backendSucceeded: true }));
      }

      logoutPromise ??= (async () => {
        let backendLogout: Promise<void>;
        try {
          backendLogout = authApi.logout();
        } catch {
          teardown(true);
          return Object.freeze({ backendSucceeded: false });
        }

        // Invalidate synchronously so an already in-flight refresh cannot win the race.
        teardown(true);
        try {
          await backendLogout;
          return Object.freeze({ backendSucceeded: true });
        } catch {
          return Object.freeze({ backendSucceeded: false });
        }
      })().finally(() => {
        logoutPromise = null;
      });
      return logoutPromise;
    },

    dispose() {
      unsubscribeRemoteSessionDestruction();
      crossTabSync.dispose();
    },
  };

  return Object.freeze(session);
}
