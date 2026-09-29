import type { User } from '@parkio/types';
import { http, HttpResponse } from 'msw';
import { act, waitFor } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { clearPendingProfile, getPendingProfile, setPendingProfile } from '@/auth/pendingProfile';
import { API_BASE, apiErrorBody, server } from '@/test/server';
import { createTestAppRuntime, createTestQueryClient, renderWithProviders } from '@/test/utils';
import { AccountPreparingPage } from '@/pages/AccountPreparingPage';

/**
 * CX-F04 / U04 follow-up: the profile PATCH must stay bound to the account that
 * owns the pending fields through the *real* SDK transport — the Axios request
 * interceptor (token attachment) and the 401 → shared refresh → retry path.
 * Every outgoing attempt is recorded with the Authorization it actually carried.
 * Synthetic identities and MSW-mocked network only.
 */
const ACCOUNT_A: User = {
  id: '1a1a1a1a-0000-4000-8000-00000000000a',
  email: 'synthetic-a@parkio.test',
  status: 'ACTIVE',
  roles: ['USER'],
};
const ACCOUNT_B: User = {
  id: '2b2b2b2b-0000-4000-8000-00000000000b',
  email: 'synthetic-b@parkio.test',
  status: 'ACTIVE',
  roles: ['USER'],
};
const A_FIELDS = { displayName: 'Registrant A', phoneNumber: '5551234567' };

interface Attempt {
  authorization: string | null;
  body: Record<string, unknown>;
}

function authResponse(user: User, accessToken: string) {
  return {
    accessToken,
    tokenType: 'Bearer',
    accessTokenExpiresAt: '2999-01-01T00:00:00Z',
    refreshTokenExpiresAt: '2999-01-01T00:00:00Z',
    user,
  };
}

function deferred() {
  let resolve!: () => void;
  const promise = new Promise<void>((r) => {
    resolve = r;
  });
  return { promise, resolve };
}

/**
 * Mock backend. `validTokens` maps bearer → account; `cookie` is the shared
 * browser refresh-cookie identity (another tab can change it).
 */
function installBackend(options: {
  validTokens: Record<string, User>;
  cookie: { user: User; token: string } | null;
  beforePatchResponse?: (attempt: Attempt) => Promise<void> | void;
}) {
  const attempts: Attempt[] = [];
  const refreshes: string[] = [];
  const state = options;
  server.use(
    http.get(`${API_BASE}/auth/me`, ({ request }) => {
      const user = state.validTokens[request.headers.get('authorization') ?? ''];
      return user
        ? HttpResponse.json(user)
        : HttpResponse.json(apiErrorBody('INVALID_TOKEN', 'expired', 'trace-me'), { status: 401 });
    }),
    http.post(`${API_BASE}/auth/refresh-token`, () => {
      if (!state.cookie) {
        return HttpResponse.json(apiErrorBody('INVALID_TOKEN', 'no session', 'trace-r'), {
          status: 401,
        });
      }
      refreshes.push(state.cookie.user.id);
      state.validTokens[`Bearer ${state.cookie.token}`] = state.cookie.user;
      return HttpResponse.json(authResponse(state.cookie.user, state.cookie.token));
    }),
    http.patch(`${API_BASE}/users/me`, async ({ request }) => {
      const attempt: Attempt = {
        authorization: request.headers.get('authorization'),
        body: (await request.json()) as Record<string, unknown>,
      };
      attempts.push(attempt);
      await state.beforePatchResponse?.(attempt);
      const user = state.validTokens[attempt.authorization ?? ''];
      return user
        ? HttpResponse.json({})
        : HttpResponse.json(apiErrorBody('INVALID_TOKEN', 'expired', 'trace-p'), { status: 401 });
    }),
  );
  return { attempts, refreshes, state };
}

function renderPreparingAs(user: User, token: string) {
  const runtime = createTestAppRuntime(createTestQueryClient(), ['/preparing']);
  runtime.authStore.getState().setSession(token, user);
  runtime.authStore.getState().beginProvisioning();
  renderWithProviders(
    <Routes>
      <Route path="/preparing" element={<AccountPreparingPage />} />
    </Routes>,
    { initialEntries: ['/preparing'], runtime },
  );
  return runtime;
}

async function settle(ms = 80) {
  await act(async () => {
    await new Promise((r) => setTimeout(r, ms));
  });
}

function sentUnder(attempts: Attempt[], token: string) {
  return attempts.filter((a) => a.authorization === `Bearer ${token}`);
}

describe('pending profile PATCH identity through the SDK transport (CX-F04)', () => {
  beforeEach(() => clearPendingProfile());
  afterEach(() => {
    clearPendingProfile();
    vi.restoreAllMocks();
  });

  it('A: session switches to B after the identity check but before the request interceptor attaches auth', async () => {
    const backend = installBackend({
      validTokens: { 'Bearer token-A': ACCOUNT_A, 'Bearer token-B': ACCOUNT_B },
      cookie: { user: ACCOUNT_A, token: 'token-A' },
    });
    setPendingProfile(A_FIELDS, ACCOUNT_A.id);
    const runtime = createTestAppRuntime(createTestQueryClient(), ['/preparing']);
    runtime.authStore.getState().setSession('token-A', ACCOUNT_A);
    runtime.authStore.getState().beginProvisioning();
    // Whichever profile mutation the page uses: switch the session synchronously
    // right after the call. Axios runs request interceptors asynchronously, so the
    // switch lands after the page's checks and before Authorization is stamped.
    const usersApi = runtime.sdk.usersApi;
    for (const method of ['updateMyProfile', 'updateMyProfileAs'] as const) {
      const original = usersApi[method].bind(usersApi) as (...args: unknown[]) => Promise<unknown>;
      vi.spyOn(usersApi, method).mockImplementation(((...args: unknown[]) => {
        const pending = original(...args);
        runtime.authStore.getState().setSession('token-B', ACCOUNT_B);
        return pending;
      }) as never);
    }
    renderWithProviders(
      <Routes>
        <Route path="/preparing" element={<AccountPreparingPage />} />
      </Routes>,
      { initialEntries: ['/preparing'], runtime },
    );

    await waitFor(() => expect(backend.attempts.length).toBeGreaterThan(0));
    await settle();

    expect(sentUnder(backend.attempts, 'token-B')).toEqual([]);
    expect(backend.attempts).toEqual([{ authorization: 'Bearer token-A', body: A_FIELDS }]);
  });

  it('B: A\'s PATCH gets 401 and the tab switches to B while the response/refresh is pending', async () => {
    const gate = deferred();
    const backend = installBackend({
      // token-A has expired server-side: the first attempt is answered 401.
      validTokens: { 'Bearer token-B': ACCOUNT_B },
      cookie: { user: ACCOUNT_A, token: 'token-A2' },
      beforePatchResponse: async (attempt) => {
        if (attempt.authorization === 'Bearer token-A') await gate.promise;
      },
    });
    server.use(http.get(`${API_BASE}/auth/me`, () => HttpResponse.json(ACCOUNT_A)));
    setPendingProfile(A_FIELDS, ACCOUNT_A.id);
    const runtime = renderPreparingAs(ACCOUNT_A, 'token-A');

    await waitFor(() => expect(backend.attempts.length).toBe(1));
    // Same tab signs in as B without the logout cleanup path; the shared refresh
    // cookie now belongs to B.
    act(() => {
      runtime.authStore.getState().setSession('token-B', ACCOUNT_B);
    });
    backend.state.cookie = { user: ACCOUNT_B, token: 'token-B2' };
    gate.resolve();
    await settle(150);

    expect(backend.attempts[0]).toEqual({ authorization: 'Bearer token-A', body: A_FIELDS });
    expect(sentUnder(backend.attempts, 'token-B')).toEqual([]);
    expect(sentUnder(backend.attempts, 'token-B2')).toEqual([]);
    expect(backend.attempts).toHaveLength(1);
    expect(runtime.authStore.getState().user?.id).toBe(ACCOUNT_B.id);
  });

  it('C: another tab changes the shared cookie identity to B before the refresh', async () => {
    const backend = installBackend({
      validTokens: {},
      // Another tab signed in as B: the refresh cookie now yields B.
      cookie: { user: ACCOUNT_B, token: 'token-B2' },
    });
    // /auth/me must succeed for A to reach the PATCH; only the PATCH sees expiry.
    server.use(http.get(`${API_BASE}/auth/me`, () => HttpResponse.json(ACCOUNT_A)));
    setPendingProfile(A_FIELDS, ACCOUNT_A.id);
    const runtime = renderPreparingAs(ACCOUNT_A, 'token-A');

    await waitFor(() => expect(backend.refreshes.length).toBe(1));
    await settle(150);

    expect(backend.attempts[0]).toEqual({ authorization: 'Bearer token-A', body: A_FIELDS });
    expect(sentUnder(backend.attempts, 'token-B2')).toEqual([]);
    expect(backend.attempts).toHaveLength(1);
    // The tab now legitimately holds B's restored session; A's data is gone.
    expect(runtime.authStore.getState().user?.id).toBe(ACCOUNT_B.id);
    expect(getPendingProfile()).toBeNull();
  });

  it('D: same-account refresh still retries once under A\'s rotated token', async () => {
    const backend = installBackend({
      validTokens: {},
      cookie: { user: ACCOUNT_A, token: 'token-A2' },
    });
    server.use(http.get(`${API_BASE}/auth/me`, () => HttpResponse.json(ACCOUNT_A)));
    setPendingProfile(A_FIELDS, ACCOUNT_A.id);
    const runtime = renderPreparingAs(ACCOUNT_A, 'token-A');

    await waitFor(() => expect(runtime.authStore.getState().lifecycle).toBe('authenticated'));
    await settle();

    expect(backend.attempts).toEqual([
      { authorization: 'Bearer token-A', body: A_FIELDS },
      { authorization: 'Bearer token-A2', body: A_FIELDS },
    ]);
    expect(backend.refreshes).toEqual([ACCOUNT_A.id]);
    expect(runtime.authStore.getState().user?.id).toBe(ACCOUNT_A.id);
  });
});
