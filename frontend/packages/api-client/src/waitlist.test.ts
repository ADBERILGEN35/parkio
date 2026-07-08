import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { createApiClient } from './client';
import { MemoryTokenStorage } from './token-storage';
import { WAITLIST_SOURCE, createWaitlistApi, isWaitlistRole } from './waitlist';

const BASE = 'http://api.test/api/v1';
const server = setupServer();

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe('waitlist api', () => {
  it('accepts only the supported optional waitlist roles', () => {
    expect(isWaitlistRole('driver')).toBe(true);
    expect(isWaitlistRole('tester')).toBe(true);
    expect(isWaitlistRole('partner')).toBe(true);
    expect(isWaitlistRole('admin')).toBe(false);
  });

  it('posts the public waitlist contract', async () => {
    let seen: unknown;
    server.use(
      http.post(`${BASE}/waitlist`, async ({ request }) => {
        seen = await request.json();
        return HttpResponse.json({ status: 'accepted' }, { status: 202 });
      }),
    );
    const api = createWaitlistApi(createApiClient({
      baseURL: BASE,
      tokenStorage: new MemoryTokenStorage(),
    }));

    await expect(api.submit({
      email: 'driver@parkio.dev',
      city: 'Izmir',
      role: 'tester',
      consentTimestamp: '2026-07-08T00:00:00.000Z',
      source: WAITLIST_SOURCE,
    })).resolves.toEqual({ status: 'accepted' });
    expect(seen).toEqual({
      email: 'driver@parkio.dev',
      city: 'Izmir',
      role: 'tester',
      consentTimestamp: '2026-07-08T00:00:00.000Z',
      source: WAITLIST_SOURCE,
    });
  });
});
