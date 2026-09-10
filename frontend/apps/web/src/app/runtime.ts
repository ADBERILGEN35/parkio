import { setRefreshHandler, type TokenStorage } from '@parkio/api-client';
import type { QueryClient } from '@tanstack/react-query';
import { createParkioSdk, type ParkioSdk } from './sdk';
import { createAuthStore, type AuthStore } from '@/auth/auth-store';
import { createAuthSession, type AuthSession } from '@/auth/session';
import { frontendConfig } from '@/config/env';
import { MemoryOnlyTokenStorage } from '@/platform/browser/token-storage';
import { createWebQueryClient } from '@/providers/query-client';
import {
  createAppRouter,
  type AppRouter,
} from '@/routing/create-app-router';
import {
  clearRankingEvaluation,
  configureRankingEvaluationApi,
} from '@/services/rankingEvaluationCorrelation';

export interface WebAppRuntime {
  readonly sdk: ParkioSdk;
  readonly queryClient: QueryClient;
  readonly authStore: AuthStore;
  readonly authSession: AuthSession;
  readonly router: AppRouter;
  dispose(): void;
}

export type WebAppRouterFactory = () => AppRouter;

export interface WebAppRuntimeOptions {
  readonly baseURL?: string;
  readonly tokenStorage?: TokenStorage;
  readonly queryClient?: QueryClient;
  readonly createRouter?: WebAppRouterFactory;
}

/**
 * Creates all mutable SDK-side application dependencies for one mounted Web runtime.
 * The function has no singleton cache; production calls it once and tests create isolated instances.
 */
export function createWebAppRuntime(options: WebAppRuntimeOptions = {}): WebAppRuntime {
  const tokenStorage = options.tokenStorage ?? new MemoryOnlyTokenStorage();
  const queryClient = options.queryClient ?? createWebQueryClient();
  const authStore = createAuthStore();
  const router = (options.createRouter ?? createAppRouter)();

  const synchronizeToken = (accessToken: string | null) => {
    if (accessToken) {
      tokenStorage.setTokens({ accessToken });
    } else {
      tokenStorage.clearTokens();
    }
  };

  synchronizeToken(authStore.getState().accessToken);
  const unsubscribeFromAuth = authStore.subscribe((state, previousState) => {
    if (state.accessToken !== previousState.accessToken) {
      synchronizeToken(state.accessToken);
    }
  });

  let authSession: AuthSession | null = null;
  const sdk = createParkioSdk({
    baseURL: options.baseURL ?? frontendConfig.apiBaseUrl,
    tokenStorage,
    onAuthFailure: () => authSession?.handleSdkUnauthorized(),
    onAccountNotActive: () => authSession?.handleAccountNotActive(),
  });

  authSession = createAuthSession({
    authApi: sdk.authApi,
    authStore,
  });
  setRefreshHandler(authSession.refreshThroughSdk);
  configureRankingEvaluationApi(sdk.parkingApi);

  let disposed = false;
  return Object.freeze({
    sdk,
    queryClient,
    authStore,
    authSession,
    router,
    dispose() {
      if (disposed) return;
      disposed = true;
      router.dispose();
      authSession?.dispose();
      unsubscribeFromAuth();
      tokenStorage.clearTokens();
      setRefreshHandler(null);
      configureRankingEvaluationApi(null);
      clearRankingEvaluation();
    },
  });
}
