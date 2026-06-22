import { refreshSession } from '@parkio/api-client';
import { http, HttpResponse } from 'msw';
import { afterEach, describe, expect, it } from 'vitest';
import { useAuthStore } from '@/auth/store';
import { API_BASE, apiErrorBody, server } from '@/test/server';
// Importing the api singleton registers the real refresh handler used by the
// shared single-flight coordinator (refreshSession).
import '@/api';

const REFRESH_URL = `${API_BASE}/auth/refresh-token`;

function authResponse(accessToken: string) {
  return {
    accessToken,
    tokenType: 'Bearer',
    accessTokenExpiresAt: '2026-06-22T12:00:00Z',
    refreshTokenExpiresAt: '2026-07-22T12:00:00Z',
    user: { id: 'u1', email: 'tester@parkio.dev', status: 'ACTIVE', roles: ['USER'] },
  };
}

afterEach(() => {
  useAuthStore.getState().clearSession();
  localStorage.clear();
});

describe('refresh handler (real coordinator + store)', () => {
  it('restores the session and keeps the access token memory-only', async () => {
    useAuthStore.setState({ accessToken: null, user: null, isAuthenticated: false });
    server.use(http.post(REFRESH_URL, () => HttpResponse.json(authResponse('mem-token'))));

    const token = await refreshSession();

    expect(token).toBe('mem-token');
    expect(useAuthStore.getState().accessToken).toBe('mem-token');
    expect(useAuthStore.getState().isAuthenticated).toBe(true);
    // Never persisted: refresh token stays in the HttpOnly cookie, access token in memory.
    expect(localStorage.getItem('parkio.accessToken')).toBeNull();
    expect(localStorage.getItem('parkio.refreshToken')).toBeNull();
  });

  it('clears the session on refresh failure, hitting the network once for concurrent callers', async () => {
    useAuthStore.setState({ accessToken: 'stale', isAuthenticated: true, bootstrapPending: true });
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
    expect(useAuthStore.getState().isAuthenticated).toBe(false);
    expect(useAuthStore.getState().accessToken).toBeNull();
  });

  it('does not resurrect a session that was logged out during an in-flight refresh', async () => {
    useAuthStore.setState({ accessToken: null, user: null, isAuthenticated: false });
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
    useAuthStore.getState().clearSession();
    release();
    const token = await pending;

    expect(token).toBeNull();
    expect(useAuthStore.getState().isAuthenticated).toBe(false);
    expect(useAuthStore.getState().accessToken).toBeNull();
  });
});
