import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { API_BASE, server } from '@/test/server';
import { WAITLIST_SOURCE, isWaitlistRole, submitWaitlistInterest } from './waitlistService';

describe('waitlistService', () => {
  it('accepts only the supported optional waitlist roles', () => {
    expect(isWaitlistRole('driver')).toBe(true);
    expect(isWaitlistRole('tester')).toBe(true);
    expect(isWaitlistRole('partner')).toBe(true);
    expect(isWaitlistRole('admin')).toBe(false);
  });

  it('rejects submissions without beta update consent before calling the API', async () => {
    await expect(
      submitWaitlistInterest({
        email: 'driver@parkio.dev',
        betaUpdatesConsent: false,
        researchConsent: false,
        consentTimestamp: '2026-07-08T00:00:00.000Z',
        source: WAITLIST_SOURCE,
      }),
    ).rejects.toThrow('Consent is required to join the waitlist.');
  });

  it('submits accepted beta update consent to the real waitlist API', async () => {
    let seen: unknown;
    server.use(
      http.post(`${API_BASE}/waitlist`, async ({ request }) => {
        seen = await request.json();
        return HttpResponse.json({ status: 'accepted' }, { status: 202 });
      }),
    );

    await expect(
      submitWaitlistInterest({
        email: 'driver@parkio.dev',
        city: 'Izmir',
        role: 'tester',
        betaUpdatesConsent: true,
        researchConsent: false,
        consentTimestamp: '2026-07-08T00:00:00.000Z',
        source: WAITLIST_SOURCE,
      }),
    ).resolves.toEqual({ status: 'accepted' });
    expect(seen).toEqual({
      email: 'driver@parkio.dev',
      city: 'Izmir',
      role: 'tester',
      consentTimestamp: '2026-07-08T00:00:00.000Z',
      source: WAITLIST_SOURCE,
    });
  });

  it('keeps duplicate accepted responses enumeration-safe', async () => {
    let submissions = 0;
    server.use(
      http.post(`${API_BASE}/waitlist`, () => {
        submissions += 1;
        return HttpResponse.json({ status: 'accepted' }, { status: 202 });
      }),
    );
    const payload = {
      email: 'existing@parkio.dev',
      betaUpdatesConsent: true,
      researchConsent: false,
      consentTimestamp: '2026-07-08T00:00:00.000Z',
      source: WAITLIST_SOURCE,
    } as const;

    await expect(submitWaitlistInterest(payload)).resolves.toEqual({ status: 'accepted' });
    await expect(submitWaitlistInterest(payload)).resolves.toEqual({ status: 'accepted' });
    expect(submissions).toBe(2);
  });
});
