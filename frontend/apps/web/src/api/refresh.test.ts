import { refreshSession, UnauthorizedError } from '@parkio/api-client';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createWebAppRuntime, type WebAppRuntime } from '@/app/runtime';
import { createMemoryAppRouter } from '@/routing/create-app-router';
import { API_BASE, apiErrorBody, server } from '@/test/server';

const REFRESH_URL = `${API_BASE}/auth/refresh-token`;
const ME_URL = `${API_BASE}/auth/me`;
let runtime: WebAppRuntime;

const primaryUser = {
  id: 'u1',
  email: 'tester@parkio.dev',
  status: 'ACTIVE',
  roles: ['USER'],
};
const replacementUser = {
  id: 'u2',
  email: 'replacement@parkio.dev',
  status: 'ACTIVE',
  roles: ['ADMIN'],
};

function authResponse(accessToken: string, user = primaryUser) {
  return {
    accessToken,
    tokenType: 'Bearer',
    accessTokenExpiresAt: '2026-06-22T12:00:00Z',
    refreshTokenExpiresAt: '2026-07-22T12:00:00Z',
    user,
  };
}

beforeEach(() => {
  runtime = createWebAppRuntime({
    baseURL: API_BASE,
    createRouter: () => createMemoryAppRouter({ initialEntries: ['/'] }),
  });
});

afterEach(() => {
  runtime.dispose();
  localStorage.clear();
});

describe('refresh handler (real coordinator + store)', () => {
  it('restores the session and keeps the access token memory-only', async () => {
    server.use(http.post(REFRESH_URL, () => HttpResponse.json(authResponse('mem-token'))));

    const token = await refreshSession();

    expect(token).toBe('mem-token');
    expect(runtime.authStore.getState().accessToken).toBe('mem-token');
    expect(runtime.authStore.getState().isAuthenticated).toBe(true);
    expect(runtime.authStore.getState().identity).toMatchObject({
      state: 'authenticated',
      userId: 'u1',
    });
    // Never persisted: refresh token stays in the HttpOnly cookie, access token in memory.
    expect(localStorage.getItem('parkio.accessToken')).toBeNull();
    expect(localStorage.getItem('parkio.refreshToken')).toBeNull();
  });

  it('clears the session on refresh failure, hitting the network once for concurrent callers', async () => {
    runtime.authStore.getState().setSession('stale', authResponse('stale').user);
    let calls = 0;
    server.use(
      http.post(REFRESH_URL, () => {
        calls += 1;
        return HttpResponse.json(apiErrorBody('INVALID_TOKEN', 'expired'), { status: 401 });
      }),
    );

    const [a, b] = await Promise.all([refreshSession(), refreshSession()]);

    expect(a).toBeNull();
    expect(b).toBeNull();
    expect(calls).toBe(1); // single-flight: one network refresh for both callers
    expect(runtime.authStore.getState().isAuthenticated).toBe(false);
    expect(runtime.authStore.getState().accessToken).toBeNull();
  });

  it('does not resurrect a session that was logged out during an in-flight refresh', async () => {
    // Created synchronously so the resolvers are real before the request fires.
    let release: () => void = () => {};
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    let markStarted: () => void = () => {};
    const handlerStarted = new Promise<void>((resolve) => {
      markStarted = resolve;
    });
    server.use(
      http.post(REFRESH_URL, async () => {
        markStarted();
        await gate;
        return HttpResponse.json(authResponse('late-token'));
      }),
    );

    const pending = refreshSession();
    await handlerStarted; // the refresh request is now genuinely in flight
    // User logs out while the refresh request is still in flight.
    runtime.authStore.getState().clearSession();
    release();
    const token = await pending;

    expect(token).toBeNull();
    expect(runtime.authStore.getState().isAuthenticated).toBe(false);
    expect(runtime.authStore.getState().accessToken).toBeNull();
  });

  it('integrates concurrent unauthorized requests with one SDK-coordinated refresh', async () => {
    runtime.authStore.getState().setSession('stale-token', authResponse('stale-token').user);
    let refreshCalls = 0;
    server.use(
      http.get(ME_URL, ({ request }) => {
        if (request.headers.get('authorization') !== 'Bearer fresh-token') {
          return HttpResponse.json(apiErrorBody('INVALID_TOKEN', 'expired'), { status: 401 });
        }
        return HttpResponse.json(authResponse('fresh-token').user);
      }),
      http.post(REFRESH_URL, () => {
        refreshCalls += 1;
        return HttpResponse.json(authResponse('fresh-token'));
      }),
    );

    const [first, second] = await Promise.all([
      runtime.sdk.authApi.me(),
      runtime.sdk.authApi.me(),
    ]);

    expect(first.id).toBe('u1');
    expect(second.id).toBe('u1');
    expect(refreshCalls).toBe(1);
    expect(runtime.authStore.getState().accessToken).toBe('fresh-token');
  });

  it('does not let a stale SDK refresh success clear or overwrite a replacement identity', async () => {
    runtime.authStore.getState().setSession('identity-a-token', primaryUser);
    let releaseRefresh: () => void = () => {};
    const refreshGate = new Promise<void>((resolve) => {
      releaseRefresh = resolve;
    });
    let markRefreshStarted: () => void = () => {};
    const refreshStarted = new Promise<void>((resolve) => {
      markRefreshStarted = resolve;
    });
    server.use(
      http.get(ME_URL, () =>
        HttpResponse.json(apiErrorBody('INVALID_TOKEN', 'expired'), { status: 401 }),
      ),
      http.post(REFRESH_URL, async () => {
        markRefreshStarted();
        await refreshGate;
        return HttpResponse.json(authResponse('late-identity-a-token'));
      }),
    );

    const request = runtime.sdk.authApi.me();
    await refreshStarted;
    runtime.authStore
      .getState()
      .setSession('identity-b-token', replacementUser);
    const replacementEpoch = runtime.authStore.getState().sessionEpoch;
    releaseRefresh();

    await expect(request).rejects.toBeInstanceOf(UnauthorizedError);
    expect(runtime.authStore.getState().accessToken).toBe('identity-b-token');
    expect(runtime.authStore.getState().identity.userId).toBe(replacementUser.id);
    expect(runtime.authStore.getState().sessionEpoch).toBe(replacementEpoch);
  });

  it('does not let a stale SDK refresh failure tear down a replacement identity', async () => {
    runtime.authStore.getState().setSession('identity-a-token', primaryUser);
    let releaseRefresh: () => void = () => {};
    const refreshGate = new Promise<void>((resolve) => {
      releaseRefresh = resolve;
    });
    let markRefreshStarted: () => void = () => {};
    const refreshStarted = new Promise<void>((resolve) => {
      markRefreshStarted = resolve;
    });
    server.use(
      http.get(ME_URL, () =>
        HttpResponse.json(apiErrorBody('INVALID_TOKEN', 'expired'), { status: 401 }),
      ),
      http.post(REFRESH_URL, async () => {
        markRefreshStarted();
        await refreshGate;
        return HttpResponse.json(
          apiErrorBody('INVALID_TOKEN', 'late refresh failure'),
          { status: 401 },
        );
      }),
    );

    const request = runtime.sdk.authApi.me();
    await refreshStarted;
    runtime.authStore
      .getState()
      .setSession('identity-b-token', replacementUser);
    const replacementEpoch = runtime.authStore.getState().sessionEpoch;
    releaseRefresh();

    await expect(request).rejects.toBeInstanceOf(UnauthorizedError);
    expect(runtime.authStore.getState().accessToken).toBe('identity-b-token');
    expect(runtime.authStore.getState().identity.userId).toBe(replacementUser.id);
    expect(runtime.authStore.getState().sessionEpoch).toBe(replacementEpoch);
  });

  it('tears down UNAUTHORIZED once after SDK refresh processing', async () => {
    runtime.authStore.getState().setSession('stale-token', authResponse('stale-token').user);
    const epochBefore = runtime.authStore.getState().sessionEpoch;
    server.use(
      http.get(ME_URL, () =>
        HttpResponse.json(apiErrorBody('INVALID_TOKEN', 'expired'), { status: 401 }),
      ),
      http.post(REFRESH_URL, () =>
        HttpResponse.json(apiErrorBody('INVALID_TOKEN', 'expired'), { status: 401 }),
      ),
    );

    await expect(runtime.sdk.authApi.me()).rejects.toBeInstanceOf(UnauthorizedError);

    expect(runtime.authStore.getState().lifecycle).toBe('anonymous');
    expect(runtime.authStore.getState().sessionEpoch).toBe(epochBefore + 1);
  });

  it('settles ACCOUNT_NOT_ACTIVE without converting it to anonymous', async () => {
    server.use(
      http.post(REFRESH_URL, () =>
        HttpResponse.json(apiErrorBody('ACCOUNT_NOT_ACTIVE', 'inactive'), { status: 403 }),
      ),
    );

    await expect(refreshSession()).resolves.toBeNull();

    expect(runtime.authStore.getState().lifecycle).toBe('account-restricted');
    expect(runtime.authStore.getState().restriction).toBe('ACCOUNT_NOT_ACTIVE');
  });

  it('settles ACCOUNT_NOT_VERIFIED without converting it to generic authorization failure', async () => {
    server.use(
      http.post(REFRESH_URL, () =>
        HttpResponse.json(apiErrorBody('ACCOUNT_NOT_VERIFIED', 'unverified'), { status: 403 }),
      ),
    );

    await expect(refreshSession()).resolves.toBeNull();

    expect(runtime.authStore.getState().lifecycle).toBe('account-restricted');
    expect(runtime.authStore.getState().restriction).toBe('ACCOUNT_NOT_VERIFIED');
  });

  it('preserves a valid authenticated session when refresh reports FORBIDDEN', async () => {
    runtime.authStore.getState().setSession('valid-token', authResponse('valid-token').user);
    server.use(
      http.post(REFRESH_URL, () =>
        HttpResponse.json(apiErrorBody('FORBIDDEN', 'forbidden'), { status: 403 }),
      ),
    );

    await expect(refreshSession()).resolves.toBeNull();

    expect(runtime.authStore.getState().lifecycle).toBe('authenticated');
    expect(runtime.authStore.getState().accessToken).toBe('valid-token');
  });
});
