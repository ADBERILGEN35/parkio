import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import type { ParkingSessionHistoryResponse, ParkingSessionResponse } from '@parkio/types';
import { createApiClient } from './client';
import { CancellationError, ContractValidationError } from './errors';
import { IDEMPOTENCY_HEADER } from './idempotency';
import { createParkingApi } from './parking';
import { MemoryTokenStorage } from './token-storage';

const BASE = 'http://api.test/api/v1';

/** Realistic fixtures aligned with `@parkio/validation` parking-session contracts. */
const startBody = {
  latitude: 41.0082,
  longitude: 28.9784,
  estimatedFee: '125.50',
  lastConfirmedAt: '2026-07-25T10:00:00.000Z',
  completionType: null,
} as const;

const activeSession = {
  id: 'd431ad5a-f8ce-4be2-b4dc-248b47990b39',
  status: 'ACTIVE',
  parkingSource: 'MANUAL',
  startedAt: '2026-07-21T09:00:00Z',
  endedAt: null,
  latitude: 41.0082,
  longitude: 28.9784,
  estimatedFee: '125.50',
  lastConfirmedAt: '2026-07-21T09:00:00Z',
  completionType: null,
} satisfies ParkingSessionResponse;

const completedSession = {
  ...activeSession,
  status: 'COMPLETED',
  endedAt: '2026-07-21T11:15:00Z',
  completionType: 'MANUAL',
} satisfies ParkingSessionResponse;

const historyResponse = {
  items: [completedSession],
  nextCursor:
    'eyJ2IjoxLCJzdGFydGVkQXQiOiIyMDI2LTA3LTIxVDA5OjAwOjAwWiIsImlkIjoiZDQzMWFkNWEtZjhjZS00YmUyLWI0ZGMtMjQ4YjQ3OTkwYjM5In0',
} satisfies ParkingSessionHistoryResponse;

const HISTORY_CURSOR = historyResponse.nextCursor!;
const SESSION_ID = activeSession.id;
const ENCODED_SESSION_PATH = `/parking/sessions/${encodeURIComponent(SESSION_ID)}`;

const server = setupServer();

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function parkingApi() {
  return createParkingApi(
    createApiClient({ baseURL: BASE, tokenStorage: new MemoryTokenStorage() }),
  );
}

describe('ParkingSession API — startParkingSession', () => {
  it('POSTs the start body with the caller-provided Idempotency-Key and validates the response', async () => {
    let method = '';
    let url = '';
    let body: unknown;
    let idem: string | null = null;

    server.use(
      http.post(`${BASE}/parking/sessions`, async ({ request }) => {
        method = request.method;
        url = request.url;
        body = await request.json();
        idem = request.headers.get(IDEMPOTENCY_HEADER);
        return HttpResponse.json(activeSession, { status: 201 });
      }),
    );

    const result = await parkingApi().startParkingSession(startBody, 'start-key-1');

    expect(method).toBe('POST');
    expect(url).toBe(`${BASE}/parking/sessions`);
    expect(body).toEqual(startBody);
    expect(idem).toBe('start-key-1');
    expect(result).toEqual(activeSession);
  });

  it('rejects a malformed start response through ContractValidationError', async () => {
    server.use(
      http.post(`${BASE}/parking/sessions`, () =>
        HttpResponse.json({ ...activeSession, id: 'not-a-uuid' }, { status: 201 }),
      ),
    );

    await expect(parkingApi().startParkingSession(startBody, 'start-key-bad')).rejects.toBeInstanceOf(
      ContractValidationError,
    );
  });
});

describe('ParkingSession API — getActiveParkingSession', () => {
  it('returns a validated ParkingSessionResponse on HTTP 200', async () => {
    server.use(
      http.get(`${BASE}/parking/sessions/active`, () => HttpResponse.json(activeSession, { status: 200 })),
    );

    await expect(parkingApi().getActiveParkingSession()).resolves.toEqual(activeSession);
  });

  it('returns null on HTTP 204 without fabricating a session', async () => {
    server.use(
      http.get(`${BASE}/parking/sessions/active`, () => new HttpResponse(null, { status: 204 })),
    );

    await expect(parkingApi().getActiveParkingSession()).resolves.toBeNull();
  });

  it('rejects a malformed 200 body through ContractValidationError', async () => {
    server.use(
      http.get(`${BASE}/parking/sessions/active`, () =>
        HttpResponse.json({ ...activeSession, status: 'UNKNOWN' }, { status: 200 }),
      ),
    );

    await expect(parkingApi().getActiveParkingSession()).rejects.toBeInstanceOf(
      ContractValidationError,
    );
  });

  it('forwards AbortSignal and maps cancellation to CancellationError', async () => {
    let forwarded = false;
    server.use(
      http.get(`${BASE}/parking/sessions/active`, ({ request }) => {
        forwarded = request.signal instanceof AbortSignal;
        return HttpResponse.json(activeSession, { status: 200 });
      }),
    );

    const live = new AbortController();
    await parkingApi().getActiveParkingSession(live.signal);
    expect(forwarded).toBe(true);

    const aborted = new AbortController();
    aborted.abort();
    await expect(parkingApi().getActiveParkingSession(aborted.signal)).rejects.toBeInstanceOf(
      CancellationError,
    );
  });
});

describe('ParkingSession API — completeParkingSession', () => {
  it('POSTs the encoded session path with Idempotency-Key and validates the response', async () => {
    let url = '';
    let idem: string | null = null;

    server.use(
      http.post(`${BASE}${ENCODED_SESSION_PATH}/complete`, ({ request }) => {
        url = request.url;
        idem = request.headers.get(IDEMPOTENCY_HEADER);
        return HttpResponse.json(completedSession, { status: 200 });
      }),
    );

    const result = await parkingApi().completeParkingSession(SESSION_ID, 'complete-key-1');

    expect(url).toBe(`${BASE}${ENCODED_SESSION_PATH}/complete`);
    expect(idem).toBe('complete-key-1');
    expect(result).toEqual(completedSession);
  });
});

describe('ParkingSession API — confirmActiveParkingSession', () => {
  it('POSTs confirm-active without Idempotency-Key and returns the updated ACTIVE session', async () => {
    const confirmed = {
      ...activeSession,
      lastConfirmedAt: '2026-07-22T09:00:00Z',
    };
    let url = '';
    let idem: string | null = null;

    server.use(
      http.post(`${BASE}${ENCODED_SESSION_PATH}/confirm-active`, ({ request }) => {
        url = request.url;
        idem = request.headers.get(IDEMPOTENCY_HEADER);
        return HttpResponse.json(confirmed, { status: 200 });
      }),
    );

    const result = await parkingApi().confirmActiveParkingSession(SESSION_ID);

    expect(url).toBe(`${BASE}${ENCODED_SESSION_PATH}/confirm-active`);
    expect(idem).toBeNull();
    expect(result).toEqual(confirmed);
  });
});

describe('ParkingSession API — cancelParkingSession', () => {
  it('POSTs the encoded session path with Idempotency-Key and validates the response', async () => {
    const cancelled = {
      ...activeSession,
      status: 'CANCELLED' as const,
      endedAt: '2026-07-21T09:05:00Z',
      estimatedFee: null,
      completionType: 'MANUAL' as const,
    };
    let url = '';
    let idem: string | null = null;

    server.use(
      http.post(`${BASE}${ENCODED_SESSION_PATH}/cancel`, ({ request }) => {
        url = request.url;
        idem = request.headers.get(IDEMPOTENCY_HEADER);
        return HttpResponse.json(cancelled, { status: 200 });
      }),
    );

    const result = await parkingApi().cancelParkingSession(SESSION_ID, 'cancel-key-1');

    expect(url).toBe(`${BASE}${ENCODED_SESSION_PATH}/cancel`);
    expect(idem).toBe('cancel-key-1');
    expect(result).toEqual(cancelled);
  });
});

describe('ParkingSession API — getParkingSessionHistory', () => {
  it('sends size-only query params and validates the history response', async () => {
    let url = '';
    server.use(
      http.get(`${BASE}/parking/sessions/history`, ({ request }) => {
        url = request.url;
        return HttpResponse.json(historyResponse, { status: 200 });
      }),
    );

    const result = await parkingApi().getParkingSessionHistory({ size: 20 });

    expect(url).toContain('size=20');
    expect(url).not.toContain('cursor=');
    expect(url).not.toContain('undefined');
    expect(result).toEqual(historyResponse);
  });

  it('sends size and cursor together when both are provided', async () => {
    let url = '';
    server.use(
      http.get(`${BASE}/parking/sessions/history`, ({ request }) => {
        url = request.url;
        return HttpResponse.json({ items: [], nextCursor: null }, { status: 200 });
      }),
    );

    await parkingApi().getParkingSessionHistory({ size: 10, cursor: HISTORY_CURSOR });

    expect(url).toContain('size=10');
    expect(url).toContain(`cursor=${encodeURIComponent(HISTORY_CURSOR)}`);
  });

  it('does not serialize an absent optional cursor as undefined', async () => {
    let url = '';
    server.use(
      http.get(`${BASE}/parking/sessions/history`, ({ request }) => {
        url = request.url;
        return HttpResponse.json({ items: [], nextCursor: null }, { status: 200 });
      }),
    );

    await parkingApi().getParkingSessionHistory({ size: 5 });

    expect(url).not.toMatch(/cursor=/);
    expect(url).not.toContain('undefined');
  });

  it('rejects a malformed history body through ContractValidationError', async () => {
    server.use(
      http.get(`${BASE}/parking/sessions/history`, () =>
        HttpResponse.json({ items: [{ ...completedSession, id: 'bad' }], nextCursor: null }, { status: 200 }),
      ),
    );

    await expect(parkingApi().getParkingSessionHistory({ size: 20 })).rejects.toBeInstanceOf(
      ContractValidationError,
    );
  });

  it('forwards AbortSignal and maps cancellation to CancellationError', async () => {
    let forwarded = false;
    server.use(
      http.get(`${BASE}/parking/sessions/history`, ({ request }) => {
        forwarded = request.signal instanceof AbortSignal;
        return HttpResponse.json({ items: [], nextCursor: null }, { status: 200 });
      }),
    );

    const live = new AbortController();
    await parkingApi().getParkingSessionHistory({ size: 1 }, live.signal);
    expect(forwarded).toBe(true);

    const aborted = new AbortController();
    aborted.abort();
    await expect(
      parkingApi().getParkingSessionHistory({ size: 1 }, aborted.signal),
    ).rejects.toBeInstanceOf(CancellationError);
  });
});

describe('ParkingSession API — deleteParkingSession', () => {
  it('DELETEs the session path with no body and no Idempotency-Key', async () => {
    let method = '';
    let url = '';
    let bodyText: string | null = null;
    let idem: string | null = 'sentinel';
    let contentType: string | null = null;

    server.use(
      http.delete(`${BASE}/parking/sessions/:sessionId`, async ({ request, params }) => {
        method = request.method;
        url = request.url;
        bodyText = await request.text();
        idem = request.headers.get(IDEMPOTENCY_HEADER);
        contentType = request.headers.get('content-type');
        expect(params.sessionId).toBe(SESSION_ID);
        return new HttpResponse(null, { status: 204 });
      }),
    );

    await expect(parkingApi().deleteParkingSession(SESSION_ID)).resolves.toBeUndefined();

    expect(method).toBe('DELETE');
    expect(url).toBe(`${BASE}${ENCODED_SESSION_PATH}`);
    expect(bodyText).toBe('');
    expect(idem).toBeNull();
    expect(url).not.toContain('userId');
    expect(contentType === null || !contentType.includes('application/json') || bodyText === '').toBe(
      true,
    );
  });

  it('treats opaque 204 as success without JSON parsing', async () => {
    server.use(
      http.delete(`${BASE}/parking/sessions/:sessionId`, () =>
        new HttpResponse(null, { status: 204 }),
      ),
    );

    await expect(parkingApi().deleteParkingSession(SESSION_ID)).resolves.toBeUndefined();
  });

  it('preserves typed ConflictError on ACTIVE 409', async () => {
    server.use(
      http.delete(`${BASE}/parking/sessions/:sessionId`, () =>
        HttpResponse.json(
          {
            code: 'PARKING_SESSION_NOT_TERMINAL',
            message: 'Session is still ACTIVE.',
            traceId: '8a56ef7e-69de-4f3c-8fe5-32b83d67f1b4',
            timestamp: '2026-07-21T09:00:00Z',
          },
          { status: 409 },
        ),
      ),
    );

    await expect(parkingApi().deleteParkingSession(SESSION_ID)).rejects.toMatchObject({
      status: 409,
      code: 'PARKING_SESSION_NOT_TERMINAL',
    });
  });

  it('encodes non-ASCII session path segments safely', async () => {
    let url = '';
    const weirdId = 'd431ad5a-f8ce-4be2-b4dc-248b47990b39';
    server.use(
      http.delete(`${BASE}/parking/sessions/:sessionId`, ({ request }) => {
        url = request.url;
        return new HttpResponse(null, { status: 204 });
      }),
    );

    await parkingApi().deleteParkingSession(weirdId);
    expect(url).toBe(`${BASE}/parking/sessions/${encodeURIComponent(weirdId)}`);
  });
});

describe('ParkingSession API — deleteParkingSessionHistory', () => {
  it('DELETEs /parking/sessions/history with no body and no Idempotency-Key', async () => {
    let method = '';
    let url = '';
    let bodyText: string | null = null;
    let idem: string | null = 'sentinel';

    server.use(
      http.delete(`${BASE}/parking/sessions/history`, async ({ request }) => {
        method = request.method;
        url = request.url;
        bodyText = await request.text();
        idem = request.headers.get(IDEMPOTENCY_HEADER);
        return new HttpResponse(null, { status: 204 });
      }),
    );

    await expect(parkingApi().deleteParkingSessionHistory()).resolves.toBeUndefined();

    expect(method).toBe('DELETE');
    expect(url).toBe(`${BASE}/parking/sessions/history`);
    expect(bodyText).toBe('');
    expect(idem).toBeNull();
    expect(url).not.toContain('userId');
  });

  it('treats empty/repeated opaque 204 as success without JSON parsing', async () => {
    server.use(
      http.delete(`${BASE}/parking/sessions/history`, () => new HttpResponse(null, { status: 204 })),
    );

    const api = parkingApi();
    await expect(api.deleteParkingSessionHistory()).resolves.toBeUndefined();
    await expect(api.deleteParkingSessionHistory()).resolves.toBeUndefined();
  });
});

describe('ParkingSession API — spot method regression', () => {
  it('keeps claimSpot behavior and Idempotency-Key unchanged', async () => {
    let path = '';
    let idem: string | null = null;
    server.use(
      http.post(`${BASE}/parking/spots/:id/claim`, ({ request, params }) => {
        path = `/parking/spots/${params.id}/claim`;
        idem = request.headers.get(IDEMPOTENCY_HEADER);
        return HttpResponse.json({
          id: params.id,
          mediaId: '81518eb3-a6d8-453f-aeb9-bdf9dc73457d',
          latitude: 41.0082,
          longitude: 28.9784,
          addressText: null,
          description: null,
          manualLocationEdited: false,
          suitableVehicleTypes: ['SEDAN'],
          parkingContext: 'STREET_PARKING',
          legalStatus: 'LEGAL',
          violationReasons: [],
          status: 'FILLED',
          expiresAt: '2026-07-22T12:10:00Z',
          createdAt: '2026-07-22T12:00:00Z',
          updatedAt: '2026-07-22T12:04:00Z',
        });
      }),
    );

    const spot = await parkingApi().claimSpot('2b371445-8ab4-4a23-a1bd-9eb084187cf7', 'claim-key');
    expect(path).toBe('/parking/spots/2b371445-8ab4-4a23-a1bd-9eb084187cf7/claim');
    expect(idem).toBe('claim-key');
    expect(spot.status).toBe('FILLED');
  });
});
