import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import {
  createApiClient,
  isRefreshInFlight,
  refreshSession,
  setRefreshHandler,
} from './client';
import { CORRELATION_HEADER } from './correlation';
import { UnauthorizedError } from './errors';
import { MemoryTokenStorage } from './token-storage';
import { createUsersApi } from './users';

const BASE = 'http://api.test/api/v1';

const server = setupServer();

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  setRefreshHandler(null);
});
afterAll(() => server.close());

function apiErrorBody(code: string) {
  return {
    code,
    message: `${code} occurred`,
    traceId: 'trace-test',
    timestamp: '2026-06-11T10:00:00Z',
  };
}

function makeClient() {
  const storage = new MemoryTokenStorage();
  const onAuthFailure = vi.fn();
  const client = createApiClient({ baseURL: BASE, tokenStorage: storage, onAuthFailure });
  return { client, storage, onAuthFailure };
}

async function waitUntil(condition: () => boolean) {
  while (!condition()) {
    await new Promise((resolve) => setTimeout(resolve, 5));
  }
}

describe('request interceptor', () => {
  it('attaches Authorization when an access token exists', async () => {
    let authHeader: string | null = null;
    server.use(
      http.get(`${BASE}/ping`, ({ request }) => {
        authHeader = request.headers.get('authorization');
        return HttpResponse.json({ ok: true });
      }),
    );

    const { client, storage } = makeClient();
    storage.setTokens({ accessToken: 'token-1' });
    await client.get('/ping');

    expect(authHeader).toBe('Bearer token-1');
  });

  it('omits Authorization when no access token exists', async () => {
    let authHeader: string | null = 'sentinel';
    server.use(
      http.get(`${BASE}/ping`, ({ request }) => {
        authHeader = request.headers.get('authorization');
        return HttpResponse.json({ ok: true });
      }),
    );

    const { client } = makeClient();
    await client.get('/ping');

    expect(authHeader).toBeNull();
  });

  it('attaches a fresh X-Correlation-Id to every request', async () => {
    const correlationIds: Array<string | null> = [];
    server.use(
      http.get(`${BASE}/ping`, ({ request }) => {
        correlationIds.push(request.headers.get(CORRELATION_HEADER));
        return HttpResponse.json({ ok: true });
      }),
    );

    const { client } = makeClient();
    await client.get('/ping');
    await client.get('/ping');

    expect(correlationIds).toHaveLength(2);
    expect(correlationIds[0]).toBeTruthy();
    expect(correlationIds[1]).toBeTruthy();
    expect(correlationIds[0]).not.toBe(correlationIds[1]);
  });

});

describe('401 refresh behavior', () => {
  it('refreshes once on 401 and retries the original request with the new token', async () => {
    const authHeaders: Array<string | null> = [];
    server.use(
      http.get(`${BASE}/users/me`, ({ request }) => {
        const auth = request.headers.get('authorization');
        authHeaders.push(auth);
        if (auth !== 'Bearer fresh-token') {
          return HttpResponse.json(apiErrorBody('INVALID_TOKEN'), { status: 401 });
        }
        return HttpResponse.json({ id: 'user-1' });
      }),
    );

    const { client, storage } = makeClient();
    storage.setTokens({ accessToken: 'stale-token' });
    const refresh = vi.fn(async () => 'fresh-token');
    setRefreshHandler(refresh);

    const response = await client.get('/users/me');

    expect(response.data).toEqual({ id: 'user-1' });
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(authHeaders).toEqual(['Bearer stale-token', 'Bearer fresh-token']);
  });

  it('never triggers a refresh for a 401 from the CSRF token read', async () => {
    server.use(
      http.get(`${BASE}/auth/csrf`, () => HttpResponse.json(apiErrorBody('MISSING_TOKEN'), { status: 401 })),
    );
    const { client, storage } = makeClient();
    storage.setTokens({ accessToken: 'stale-token' });
    const refresh = vi.fn(async () => 'fresh-token');
    setRefreshHandler(refresh);

    await expect(client.get('/auth/csrf')).rejects.toBeInstanceOf(UnauthorizedError);
    expect(refresh).not.toHaveBeenCalled();
  });

  it('shares a single in-flight refresh across concurrent 401s', async () => {
    let staleResponses = 0;
    server.use(
      http.get(`${BASE}/users/me`, ({ request }) => {
        if (request.headers.get('authorization') !== 'Bearer fresh-token') {
          staleResponses += 1;
          return HttpResponse.json(apiErrorBody('INVALID_TOKEN'), { status: 401 });
        }
        return HttpResponse.json({ id: 'user-1' });
      }),
    );

    const { client, storage } = makeClient();
    storage.setTokens({ accessToken: 'stale-token' });
    // Hold the refresh open until both requests have received their 401, so both
    // are forced to await the same in-flight promise.
    const refresh = vi.fn(async () => {
      await waitUntil(() => staleResponses === 2);
      return 'fresh-token';
    });
    setRefreshHandler(refresh);

    const [a, b] = await Promise.all([client.get('/users/me'), client.get('/users/me')]);

    expect(a.data).toEqual({ id: 'user-1' });
    expect(b.data).toEqual({ id: 'user-1' });
    expect(refresh).toHaveBeenCalledTimes(1);
  });

  it('hard-logs-out via onAuthFailure when refresh fails', async () => {
    server.use(
      http.get(`${BASE}/users/me`, () =>
        HttpResponse.json(apiErrorBody('INVALID_TOKEN'), { status: 401 }),
      ),
    );

    const { client, storage, onAuthFailure } = makeClient();
    storage.setTokens({ accessToken: 'stale-token' });
    setRefreshHandler(vi.fn(async () => null));

    await expect(client.get('/users/me')).rejects.toBeInstanceOf(UnauthorizedError);
    expect(onAuthFailure).toHaveBeenCalledTimes(1);
  });

  it.each(['/auth/login', '/auth/register', '/auth/refresh-token', '/auth/logout'])(
    'does not refresh on 401 from exempt path %s',
    async (path) => {
      server.use(
        http.post(`${BASE}${path}`, () =>
          HttpResponse.json(apiErrorBody('INVALID_CREDENTIALS'), { status: 401 }),
        ),
      );

      const { client } = makeClient();
      const refresh = vi.fn(async () => 'fresh-token');
      setRefreshHandler(refresh);

      await expect(client.post(path, {})).rejects.toBeInstanceOf(UnauthorizedError);
      expect(refresh).not.toHaveBeenCalled();
    },
  );
});

describe('per-request auth binding', () => {
  interface Attempt {
    authorization: string | null;
    body: unknown;
  }

  /** PATCH /users/me accepted only for `validToken`; every attempt is recorded. */
  function patchEndpoint(validToken: string) {
    const attempts: Attempt[] = [];
    server.use(
      http.patch(`${BASE}/users/me`, async ({ request }) => {
        const authorization = request.headers.get('authorization');
        attempts.push({ authorization, body: await request.json() });
        if (authorization !== `Bearer ${validToken}`) {
          return HttpResponse.json(apiErrorBody('INVALID_TOKEN'), { status: 401 });
        }
        return HttpResponse.json({ ok: true });
      }),
    );
    return attempts;
  }

  it('stamps the bound token even when storage switched to another identity', async () => {
    const attempts = patchEndpoint('token-a');
    const { client, storage } = makeClient();
    storage.setTokens({ accessToken: 'token-a' });
    const pending = createUsersApi(client).updateMyProfileAs(
      { accessToken: 'token-a' },
      { displayName: 'A' },
    );
    // Before the (asynchronous) request interceptor runs.
    storage.setTokens({ accessToken: 'token-b' });
    await pending;

    expect(attempts).toEqual([{ authorization: 'Bearer token-a', body: { displayName: 'A' } }]);
  });

  it('keeps unbound requests on the token present when the interceptor runs', async () => {
    const attempts = patchEndpoint('token-b');
    const { client, storage } = makeClient();
    storage.setTokens({ accessToken: 'token-a' });
    const pending = createUsersApi(client).updateMyProfile({ displayName: 'X' });
    storage.setTokens({ accessToken: 'token-b' });
    await pending;

    expect(attempts.map((a) => a.authorization)).toEqual(['Bearer token-b']);
  });

  it('never retries a bound request without allowRetry, but still syncs the refreshed token', async () => {
    const attempts = patchEndpoint('token-b2');
    const { client, storage, onAuthFailure } = makeClient();
    storage.setTokens({ accessToken: 'token-a' });
    const refresh = vi.fn(async () => 'token-b2');
    setRefreshHandler(refresh);

    await expect(
      createUsersApi(client).updateMyProfileAs(
        { accessToken: 'token-a' },
        { displayName: 'A' },
      ),
    ).rejects.toBeInstanceOf(UnauthorizedError);

    expect(refresh).toHaveBeenCalledTimes(1);
    expect(attempts.map((a) => a.authorization)).toEqual(['Bearer token-a']);
    expect(storage.getAccessToken()).toBe('token-b2');
    // The refreshed session is valid; this is not a hard logout.
    expect(onAuthFailure).not.toHaveBeenCalled();
  });

  it('does not retry when allowRetry rejects the refreshed session', async () => {
    const attempts = patchEndpoint('token-b2');
    const { client, storage } = makeClient();
    storage.setTokens({ accessToken: 'token-a' });
    setRefreshHandler(async () => 'token-b2');
    const allowRetry = vi.fn(() => false);

    await expect(
      createUsersApi(client).updateMyProfileAs(
        { accessToken: 'token-a', allowRetry },
        { displayName: 'A' },
      ),
    ).rejects.toBeInstanceOf(UnauthorizedError);

    expect(allowRetry).toHaveBeenCalledWith('token-b2');
    expect(attempts.map((a) => a.authorization)).toEqual(['Bearer token-a']);
  });

  it('retries once under the refreshed token when allowRetry confirms the identity', async () => {
    const attempts = patchEndpoint('token-a2');
    const { client, storage } = makeClient();
    storage.setTokens({ accessToken: 'token-a' });
    setRefreshHandler(async () => 'token-a2');

    await expect(
      createUsersApi(client).updateMyProfileAs(
        { accessToken: 'token-a', allowRetry: () => true },
        { displayName: 'A' },
      ),
    ).resolves.toEqual({ ok: true });

    expect(attempts).toEqual([
      { authorization: 'Bearer token-a', body: { displayName: 'A' } },
      { authorization: 'Bearer token-a2', body: { displayName: 'A' } },
    ]);
  });

  it('still hard-logs-out a bound request when refresh fails', async () => {
    patchEndpoint('never');
    const { client, storage, onAuthFailure } = makeClient();
    storage.setTokens({ accessToken: 'token-a' });
    setRefreshHandler(async () => null);

    await expect(
      createUsersApi(client).updateMyProfileAs(
        { accessToken: 'token-a', allowRetry: () => true },
        { displayName: 'A' },
      ),
    ).rejects.toBeInstanceOf(UnauthorizedError);
    expect(onAuthFailure).toHaveBeenCalledTimes(1);
  });
});

describe('refreshSession single-flight coordinator', () => {
  it('resolves null when no handler is configured', async () => {
    setRefreshHandler(null);
    await expect(refreshSession()).resolves.toBeNull();
    expect(isRefreshInFlight()).toBe(false);
  });

  it('collapses concurrent callers into one handler invocation', async () => {
    let release: (token: string) => void = () => {};
    const refresh = vi.fn(
      () =>
        new Promise<string>((resolve) => {
          release = resolve;
        }),
    );
    setRefreshHandler(refresh);

    const a = refreshSession();
    const b = refreshSession();
    const c = refreshSession();
    expect(isRefreshInFlight()).toBe(true);

    release('shared-token');
    await expect(Promise.all([a, b, c])).resolves.toEqual([
      'shared-token',
      'shared-token',
      'shared-token',
    ]);
    expect(refresh).toHaveBeenCalledTimes(1);
    // In-flight promise is cleared after settling.
    expect(isRefreshInFlight()).toBe(false);
  });

  it('starts a fresh request once the previous one has settled', async () => {
    const refresh = vi.fn(async () => 'token');
    setRefreshHandler(refresh);

    await refreshSession();
    await refreshSession();

    expect(refresh).toHaveBeenCalledTimes(2);
  });

  it('rejects all shared callers once and clears in-flight on handler failure', async () => {
    const refresh = vi.fn(async () => {
      throw new Error('refresh boom');
    });
    setRefreshHandler(refresh);

    const a = refreshSession();
    const b = refreshSession();

    await expect(a).rejects.toThrow('refresh boom');
    await expect(b).rejects.toThrow('refresh boom');
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(isRefreshInFlight()).toBe(false);
  });
});
