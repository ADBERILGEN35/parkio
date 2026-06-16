import type { AxiosInstance } from 'axios';
import { describe, expect, it, vi } from 'vitest';
import { createAuthApi } from './auth';

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
});
