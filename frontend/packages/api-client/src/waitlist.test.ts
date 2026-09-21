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

  it('loads admin summary and paginated list', async () => {
    server.use(
      http.get(`${BASE}/waitlist/admin/summary`, () =>
        HttpResponse.json({ pending: 1, confirmed: 2, withdrawn: 0, total: 3 }),
      ),
      http.get(`${BASE}/waitlist/admin`, ({ request }) => {
        const url = new URL(request.url);
        expect(url.searchParams.get('status')).toBe('CONFIRMED');
        return HttpResponse.json({
          content: [
            {
              id: '6f9619ff-8b86-4d01-b42d-00cf4fc964ff',
              email: 'confirmed@parkio.dev',
              status: 'CONFIRMED',
              locale: 'en',
              source: WAITLIST_SOURCE,
              createdAt: '2026-09-21T10:00:00Z',
              confirmedAt: '2026-09-21T10:05:00Z',
              withdrawnAt: null,
            },
          ],
          page: 0,
          size: 20,
          totalElements: 1,
          totalPages: 1,
        });
      }),
    );
    const api = createWaitlistApi(
      createApiClient({ baseURL: BASE, tokenStorage: new MemoryTokenStorage() }),
    );
    await expect(api.adminSummary()).resolves.toEqual({
      pending: 1,
      confirmed: 2,
      withdrawn: 0,
      total: 3,
    });
    await expect(api.adminList({ status: 'CONFIRMED', page: 0, size: 20 })).resolves.toMatchObject({
      totalElements: 1,
      content: [{ email: 'confirmed@parkio.dev', status: 'CONFIRMED' }],
    });
  });

  it('downloads confirmed-only CSV export as text', async () => {
    server.use(
      http.get(`${BASE}/waitlist/export`, () =>
        HttpResponse.text('email,city,role,source,createdAt,consentTimestamp\n"a@b.c",,,,,\n', {
          headers: { 'Content-Type': 'text/csv' },
        }),
      ),
    );
    const api = createWaitlistApi(
      createApiClient({ baseURL: BASE, tokenStorage: new MemoryTokenStorage() }),
    );
    const csv = await api.exportConfirmedCsv();
    expect(csv).toContain('email,city');
    expect(csv).toContain('a@b.c');
  });
});
