import {
  AccountNotActiveError,
  AccountNotVerifiedError,
  ForbiddenError,
  setRefreshHandler,
  type AuthApi,
} from '@parkio/api-client';
import type { AuthResponse, User } from '@parkio/types';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createAuthStore, type AuthStore } from './auth-store';
import type { CrossTabSessionSync } from './crossTabSync';
import { setPendingProfile } from './pendingProfile';
import { createAuthSession, type AuthSession } from './session';

const user: User = {
  id: '6f9619ff-8b86-4d01-b42d-00cf4fc964ff',
  email: 'tester@parkio.dev',
  status: 'ACTIVE',
  roles: ['USER'],
};

const replacementUser: User = {
  id: '0f4af9c8-92db-49b1-bb3e-e1e09f727c91',
  email: 'replacement@parkio.dev',
  status: 'ACTIVE',
  roles: ['ADMIN'],
};

function authResponse(accessToken = 'fresh-token'): AuthResponse {
  return {
    accessToken,
    tokenType: 'Bearer',
    accessTokenExpiresAt: '2026-07-23T12:00:00Z',
    refreshTokenExpiresAt: '2026-08-23T12:00:00Z',
    user,
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

function errorBody(code: string) {
  return {
    code,
    message: code,
    traceId: 'trace-auth-session',
    timestamp: '2026-07-23T10:00:00Z',
  };
}

class FakeCrossTabSync implements CrossTabSessionSync {
  readonly broadcastSessionDestruction = vi.fn();
  readonly dispose = vi.fn();
  private readonly listeners = new Set<() => void>();

  subscribe(listener: () => void) {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  emitRemoteDestruction() {
    this.listeners.forEach((listener) => listener());
  }
}

let authStore: AuthStore;
let authApi: Pick<AuthApi, 'logout' | 'refresh'>;
let crossTab: FakeCrossTabSync;
let authSession: AuthSession;

beforeEach(() => {
  sessionStorage.clear();
  localStorage.clear();
  authStore = createAuthStore();
  authApi = {
    logout: vi.fn(async () => undefined),
    refresh: vi.fn(async () => authResponse()),
  };
  crossTab = new FakeCrossTabSync();
  authSession = createAuthSession({ authApi, authStore, crossTabSync: crossTab });
  setRefreshHandler(authSession.refreshThroughSdk);
});

afterEach(() => {
  authSession.dispose();
  setRefreshHandler(null);
  vi.restoreAllMocks();
});

describe('deterministic authentication bootstrap', () => {
  it('settles public authentication entry as anonymous without restoration', async () => {
    await expect(authSession.bootstrap('public')).resolves.toBe('anonymous');

    expect(authApi.refresh).not.toHaveBeenCalled();
    expect(authStore.getState().lifecycle).toBe('anonymous');
  });

  it('settles protected entry as anonymous when SDK restoration fails', async () => {
    vi.mocked(authApi.refresh).mockRejectedValueOnce(new Error('no refresh cookie'));

    await expect(authSession.bootstrap('protected')).resolves.toBe('anonymous');

    expect(authApi.refresh).toHaveBeenCalledTimes(1);
    expect(authStore.getState().lifecycle).toBe('anonymous');
    expect(crossTab.broadcastSessionDestruction).toHaveBeenCalledTimes(1);
  });

  it('settles a successful protected bootstrap as authenticated', async () => {
    await expect(authSession.bootstrap('protected')).resolves.toBe('authenticated');

    expect(authStore.getState().identity.state).toBe('authenticated');
    expect(authStore.getState().accessToken).toBe('fresh-token');
  });

  it('settles restoration with pending profile data as provisioning', async () => {
    setPendingProfile({ displayName: 'New Driver' });

    await expect(authSession.bootstrap('protected')).resolves.toBe('provisioning');

    expect(authStore.getState().provisioning).toBe(true);
    expect(authStore.getState().identity.state).toBe('provisioning');
  });

  it('settles ACCOUNT_NOT_ACTIVE as account-restricted', async () => {
    vi.mocked(authApi.refresh).mockRejectedValueOnce(
      new AccountNotActiveError(errorBody('ACCOUNT_NOT_ACTIVE')),
    );

    await expect(authSession.bootstrap('protected')).resolves.toBe('account-restricted');

    expect(authStore.getState().restriction).toBe('ACCOUNT_NOT_ACTIVE');
    expect(authStore.getState().suspended).toBe(true);
    expect(crossTab.broadcastSessionDestruction).not.toHaveBeenCalled();
  });

  it('settles ACCOUNT_NOT_VERIFIED distinctly as account-restricted', async () => {
    vi.mocked(authApi.refresh).mockRejectedValueOnce(
      new AccountNotVerifiedError(errorBody('ACCOUNT_NOT_VERIFIED')),
    );

    await expect(authSession.bootstrap('protected')).resolves.toBe('account-restricted');

    expect(authStore.getState().restriction).toBe('ACCOUNT_NOT_VERIFIED');
    expect(authStore.getState().suspended).toBe(false);
  });

  it('shares overlapping bootstrap calls through the SDK coordinator', async () => {
    let release: (response: AuthResponse) => void = () => {};
    vi.mocked(authApi.refresh).mockImplementationOnce(
      () =>
        new Promise<AuthResponse>((resolve) => {
          release = resolve;
        }),
    );

    const first = authSession.bootstrap('protected');
    const second = authSession.bootstrap('protected');
    release(authResponse('shared-token'));

    await expect(Promise.all([first, second])).resolves.toEqual([
      'authenticated',
      'authenticated',
    ]);
    expect(authApi.refresh).toHaveBeenCalledTimes(1);
  });
});

describe('refresh convergence and stale-result protection', () => {
  it('synchronizes identity after a successful refresh', async () => {
    const changes = vi.fn();
    authStore.subscribeIdentityChanges(changes);

    await expect(authSession.refreshThroughSdk()).resolves.toBe('fresh-token');

    expect(authStore.getState().identity.userId).toBe(user.id);
    expect(changes).toHaveBeenCalledTimes(1);
  });

  it('tears down a refresh failure once and emits one invalidation', async () => {
    authStore.getState().setSession('stale-token', user);
    const epochBeforeFailure = authStore.getState().sessionEpoch;
    vi.mocked(authApi.refresh).mockRejectedValueOnce(new Error('refresh failed'));

    await expect(authSession.refreshThroughSdk()).resolves.toBeNull();
    authSession.handleSdkUnauthorized();

    expect(authStore.getState().lifecycle).toBe('anonymous');
    expect(authStore.getState().sessionEpoch).toBe(epochBeforeFailure + 1);
    expect(crossTab.broadcastSessionDestruction).toHaveBeenCalledTimes(1);
  });

  it('does not convert FORBIDDEN into anonymous state', async () => {
    authStore.getState().setSession('valid-token', user);
    vi.mocked(authApi.refresh).mockRejectedValueOnce(
      new ForbiddenError(errorBody('FORBIDDEN')),
    );

    await expect(authSession.refreshThroughSdk()).resolves.toBeNull();
    authSession.handleSdkUnauthorized();

    expect(authStore.getState().lifecycle).toBe('authenticated');
    expect(authStore.getState().accessToken).toBe('valid-token');
    expect(crossTab.broadcastSessionDestruction).not.toHaveBeenCalled();
  });

  it('prevents logout during refresh from restoring the late result', async () => {
    authStore.getState().setSession('initial-token', user);
    let release: (response: AuthResponse) => void = () => {};
    vi.mocked(authApi.refresh).mockImplementationOnce(
      () =>
        new Promise<AuthResponse>((resolve) => {
          release = resolve;
        }),
    );

    const refresh = authSession.refreshThroughSdk();
    const logout = authSession.logout();
    release(authResponse('late-token'));

    await expect(refresh).resolves.toBeNull();
    authSession.handleSdkUnauthorized();
    await expect(logout).resolves.toEqual({ backendSucceeded: true });
    expect(authStore.getState().accessToken).toBeNull();
    expect(authStore.getState().lifecycle).toBe('anonymous');
  });

  it('does not let a late refresh for identity A overwrite identity B', async () => {
    authStore.getState().setSession('identity-a-token', user);
    const refreshResult = deferred<AuthResponse>();
    vi.mocked(authApi.refresh).mockReturnValueOnce(refreshResult.promise);

    const refresh = authSession.refreshThroughSdk();
    authStore.getState().setSession('identity-b-token', replacementUser);
    const replacementEpoch = authStore.getState().sessionEpoch;
    refreshResult.resolve(authResponse('late-identity-a-token'));

    await expect(refresh).resolves.toBeNull();
    authSession.handleSdkUnauthorized();

    expect(authStore.getState().accessToken).toBe('identity-b-token');
    expect(authStore.getState().identity.userId).toBe(replacementUser.id);
    expect(authStore.getState().sessionEpoch).toBe(replacementEpoch);
  });

  it('does not let a stale refresh failure tear down identity B', async () => {
    authStore.getState().setSession('identity-a-token', user);
    const refreshResult = deferred<AuthResponse>();
    vi.mocked(authApi.refresh).mockReturnValueOnce(refreshResult.promise);

    const refresh = authSession.refreshThroughSdk();
    authStore.getState().setSession('identity-b-token', replacementUser);
    const replacementEpoch = authStore.getState().sessionEpoch;
    refreshResult.reject(new Error('late refresh failure'));

    await expect(refresh).resolves.toBeNull();
    authSession.handleSdkUnauthorized();

    expect(authStore.getState().accessToken).toBe('identity-b-token');
    expect(authStore.getState().identity.userId).toBe(replacementUser.id);
    expect(authStore.getState().sessionEpoch).toBe(replacementEpoch);
    expect(crossTab.broadcastSessionDestruction).not.toHaveBeenCalled();
  });

  it('does not let a late refresh overwrite a newer account-state transition', async () => {
    authStore.getState().setSession('identity-a-token', user);
    const refreshResult = deferred<AuthResponse>();
    vi.mocked(authApi.refresh).mockReturnValueOnce(refreshResult.promise);

    const refresh = authSession.refreshThroughSdk();
    authStore.getState().markAccountRestricted('ACCOUNT_NOT_ACTIVE');
    const restrictedEpoch = authStore.getState().sessionEpoch;
    refreshResult.resolve(authResponse('late-active-token'));

    await expect(refresh).resolves.toBeNull();
    authSession.handleSdkUnauthorized();

    expect(authStore.getState().lifecycle).toBe('account-restricted');
    expect(authStore.getState().restriction).toBe('ACCOUNT_NOT_ACTIVE');
    expect(authStore.getState().sessionEpoch).toBe(restrictedEpoch);
  });

  it('does not let a late refresh restore a remotely invalidated session', async () => {
    authStore.getState().setSession('identity-a-token', user);
    const refreshResult = deferred<AuthResponse>();
    vi.mocked(authApi.refresh).mockReturnValueOnce(refreshResult.promise);

    const refresh = authSession.refreshThroughSdk();
    crossTab.emitRemoteDestruction();
    const invalidatedEpoch = authStore.getState().sessionEpoch;
    refreshResult.resolve(authResponse('late-token'));

    await expect(refresh).resolves.toBeNull();
    authSession.handleSdkUnauthorized();

    expect(authStore.getState().lifecycle).toBe('anonymous');
    expect(authStore.getState().accessToken).toBeNull();
    expect(authStore.getState().sessionEpoch).toBe(invalidatedEpoch);
    expect(crossTab.broadcastSessionDestruction).not.toHaveBeenCalled();
  });
});

describe('logout and remote invalidation', () => {
  it('clears and broadcasts after successful SDK logout', async () => {
    authStore.getState().setSession('access', user);

    await expect(authSession.logout()).resolves.toEqual({ backendSucceeded: true });

    expect(authApi.logout).toHaveBeenCalledTimes(1);
    expect(authStore.getState().lifecycle).toBe('anonymous');
    expect(crossTab.broadcastSessionDestruction).toHaveBeenCalledTimes(1);
  });

  it('clears and broadcasts when backend logout fails', async () => {
    authStore.getState().setSession('access', user);
    vi.mocked(authApi.logout).mockRejectedValueOnce(new Error('offline'));

    await expect(authSession.logout()).resolves.toEqual({ backendSucceeded: false });

    expect(authStore.getState().lifecycle).toBe('anonymous');
    expect(crossTab.broadcastSessionDestruction).toHaveBeenCalledTimes(1);
  });

  it('keeps repeated logout idempotent', async () => {
    authStore.getState().setSession('access', user);
    const epochBeforeLogout = authStore.getState().sessionEpoch;

    await authSession.logout();
    await authSession.logout();

    expect(authApi.logout).toHaveBeenCalledTimes(1);
    expect(authStore.getState().sessionEpoch).toBe(epochBeforeLogout + 1);
    expect(crossTab.broadcastSessionDestruction).toHaveBeenCalledTimes(1);
  });

  it('applies remote destruction locally without echoing it', () => {
    authStore.getState().setSession('access', user);
    const epochBeforeRemoteDestruction = authStore.getState().sessionEpoch;

    crossTab.emitRemoteDestruction();
    crossTab.emitRemoteDestruction();

    expect(authStore.getState().lifecycle).toBe('anonymous');
    expect(authStore.getState().sessionEpoch).toBe(epochBeforeRemoteDestruction + 1);
    expect(crossTab.broadcastSessionDestruction).not.toHaveBeenCalled();
  });
});
