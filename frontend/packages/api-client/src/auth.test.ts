import type { AxiosInstance } from 'axios';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { createAuthApi, currentCsrfToken, resetCsrfToken } from './auth';
import { createApiClient } from './client';
import { ForbiddenError } from './sdk-errors';
import { MemoryTokenStorage } from './token-storage';

function apiError(code: string) {
  return { code, message: `error ${code}`, traceId: 'trace-1', timestamp: '2026-10-08T00:00:00Z' };
}

function fakeClient() {
  return {
    post: vi.fn(async () => ({ data: {} })),
    get: vi.fn(async () => ({ data: {} })),
  } as unknown as AxiosInstance & {
    post: ReturnType<typeof vi.fn>;
    get: ReturnType<typeof vi.fn>;
  };
}

describe('auth api credentials', () => {
  it('sends credentials on cookie-backed auth endpoints', async () => {
    const client = fakeClient();
    const auth = createAuthApi(client);

    await auth.register({ email: 'user@example.com', password: 'password1' });
    await auth.login({ email: 'user@example.com', password: 'password1' });
    await auth.refresh();
    await auth.logout();
    await auth.verifyEmail({ token: 'verify-token' });
    await auth.resendVerification({ email: 'user@example.com' });

    expect(client.post).toHaveBeenNthCalledWith(1, '/auth/register', expect.any(Object), {
      withCredentials: true,
    });
    expect(client.post).toHaveBeenNthCalledWith(2, '/auth/login', expect.any(Object), {
      withCredentials: true,
    });
    expect(client.post).toHaveBeenNthCalledWith(3, '/auth/refresh-token', undefined, {
      withCredentials: true,
    });
    expect(client.post).toHaveBeenNthCalledWith(4, '/auth/logout', undefined, {
      withCredentials: true,
    });
    expect(client.post).toHaveBeenNthCalledWith(5, '/auth/verify-email', {
      token: 'verify-token',
    });
    expect(client.post).toHaveBeenNthCalledWith(6, '/auth/resend-verification', {
      email: 'user@example.com',
    });
  });

  it('sends mobile refresh tokens in refresh/logout request bodies when provided', async () => {
    const client = fakeClient();
    const auth = createAuthApi(client);

    await auth.refresh('mobile-refresh-token');
    await auth.logout('mobile-refresh-token');

    expect(client.post).toHaveBeenNthCalledWith(
      1,
      '/auth/refresh-token',
      { refreshToken: 'mobile-refresh-token' },
      { withCredentials: true },
    );
    expect(client.post).toHaveBeenNthCalledWith(
      2,
      '/auth/logout',
      { refreshToken: 'mobile-refresh-token' },
      { withCredentials: true },
    );
  });
});

describe('cookie-transport CSRF token', () => {
  it('remembers the token from a web login and sends it on cookie refresh and logout', async () => {
    resetCsrfToken();
    const client = fakeClient();
    client.post.mockImplementation(async (path: string) =>
      path === '/auth/login' ? { data: { csrfToken: 'tok-1', user: {} } } : { data: {} },
    );
    const auth = createAuthApi(client);
    await auth.login({ email: 'user@example.com', password: 'password1' });
    expect(currentCsrfToken()).toBe('tok-1');
    await auth.refresh();
    await auth.logout();
    expect(client.post).toHaveBeenNthCalledWith(2, '/auth/refresh-token', undefined, {
      withCredentials: true,
      headers: { 'X-XSRF-TOKEN': 'tok-1' },
    });
    expect(client.post).toHaveBeenNthCalledWith(3, '/auth/logout', undefined, {
      withCredentials: true,
      headers: { 'X-XSRF-TOKEN': 'tok-1' },
    });
    expect(client.get).not.toHaveBeenCalled();
  });

  it('fetches a token and retries once when a cookie refresh is refused without one', async () => {
    resetCsrfToken();
    const client = fakeClient();
    // What the client's response interceptor rejects with for 403 CSRF_TOKEN_REQUIRED.
    const forbidden = new ForbiddenError(apiError('CSRF_TOKEN_REQUIRED'));
    client.post.mockImplementation(
      async (_path: string, _body: unknown, config: { headers?: Record<string, string> }) => {
        if (!config.headers?.['X-XSRF-TOKEN']) {
          throw forbidden;
        }
        return { data: { accessToken: 'a', csrfToken: 'tok-2', user: {} } };
      },
    );
    client.get.mockResolvedValue({ data: { token: 'tok-2' } });
    const auth = createAuthApi(client);
    const result = await auth.refresh();
    expect(result.accessToken).toBe('a');
    expect(client.get).toHaveBeenCalledWith('/auth/csrf', { withCredentials: true });
    expect(client.post).toHaveBeenCalledTimes(2);
    expect(client.post).toHaveBeenLastCalledWith('/auth/refresh-token', undefined, {
      withCredentials: true,
      headers: { 'X-XSRF-TOKEN': 'tok-2' },
    });
    expect(currentCsrfToken()).toBe('tok-2');
  });

  it('does not fetch a token for other failures and never adds the header to native body-token calls', async () => {
    resetCsrfToken();
    const client = fakeClient();
    const unauthorized = Object.assign(new Error('unauthorized'), { response: { status: 401 } });
    client.post.mockRejectedValueOnce(unauthorized);
    const auth = createAuthApi(client);
    await expect(auth.refresh()).rejects.toBe(unauthorized);
    expect(client.get).not.toHaveBeenCalled();
    const plainForbidden = new ForbiddenError(apiError('FORBIDDEN'));
    client.post.mockRejectedValueOnce(plainForbidden);
    await expect(auth.logout()).rejects.toBe(plainForbidden);
    expect(client.get).not.toHaveBeenCalled();
    client.post.mockResolvedValue({ data: { refreshToken: 'native-2', user: {} } });
    await auth.refresh('native-1');
    await auth.logout('native-2');
    expect(client.post).toHaveBeenLastCalledWith('/auth/logout', { refreshToken: 'native-2' }, {
      withCredentials: true,
    });
    expect(currentCsrfToken()).toBeNull();
  });
});

describe('cookie-transport CSRF through the real client (response interceptor)', () => {
  const BASE = 'http://api.test';
  const server = setupServer();
  beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
  afterEach(() => server.resetHandlers());
  afterAll(() => server.close());

  function realClient() {
    return createApiClient({ baseURL: BASE, tokenStorage: new MemoryTokenStorage(), onAuthFailure: vi.fn() });
  }

  it('recovers from CSRF_TOKEN_REQUIRED by reading /auth/csrf once and retrying with the header', async () => {
    resetCsrfToken();
    const seen: string[] = [];
    server.use(
      http.post(`${BASE}/auth/refresh-token`, ({ request }) => {
        const header = request.headers.get('x-xsrf-token');
        seen.push(`refresh:${header ?? '-'}`);
        if (header !== 'tok-9') {
          return HttpResponse.json(apiError('CSRF_TOKEN_REQUIRED'), { status: 403 });
        }
        return HttpResponse.json({ accessToken: 'a-9', csrfToken: 'tok-9', user: {} });
      }),
      http.get(`${BASE}/auth/csrf`, ({ request }) => {
        seen.push(`csrf:${request.headers.get('authorization') ?? '-'}`);
        return HttpResponse.json({ token: 'tok-9' });
      }),
    );
    const auth = createAuthApi(realClient());
    const result = await auth.refresh();
    expect(result.accessToken).toBe('a-9');
    expect(seen).toEqual(['refresh:-', 'csrf:-', 'refresh:tok-9']);
    expect(currentCsrfToken()).toBe('tok-9');
  });

  it('surfaces a plain 403 as ForbiddenError without reading a token', async () => {
    resetCsrfToken();
    let csrfReads = 0;
    server.use(
      http.post(`${BASE}/auth/logout`, () => HttpResponse.json(apiError('FORBIDDEN'), { status: 403 })),
      http.get(`${BASE}/auth/csrf`, () => {
        csrfReads += 1;
        return HttpResponse.json({ token: 'never-used' });
      }),
    );
    const auth = createAuthApi(realClient());
    await expect(auth.logout()).rejects.toBeInstanceOf(ForbiddenError);
    expect(csrfReads).toBe(0);
    expect(currentCsrfToken()).toBeNull();
  });
});
