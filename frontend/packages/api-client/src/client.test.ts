import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { createApiClient, DEFAULT_API_BASE_URL, setRefreshHandler } from './client';
import { CORRELATION_HEADER } from './correlation';
import { UnauthorizedError } from './errors';
import { MemoryTokenStorage } from './token-storage';

const BASE = DEFAULT_API_BASE_URL;

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
  const client = createApiClient({ tokenStorage: storage, onAuthFailure });
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
