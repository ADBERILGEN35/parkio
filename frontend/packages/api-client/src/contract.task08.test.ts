import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import * as apiClient from './index';
import {
  CancellationError,
  ConflictError,
  IDEMPOTENCY_HEADER,
  MemoryTokenStorage,
  NetworkError,
  NotFoundError,
  TimeoutError,
  UnauthorizedError,
  ValidationError,
  createApiClient,
  createParkingApi,
  createIdempotencyKey,
  parseApiError,
  toParkioError,
} from './index';

const BASE = 'http://api.test/api/v1';
const server = setupServer();

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

/** Task 8 shared-client contract gate — serialization, auth, errors, exports. */
describe('Task 8 api-client contract', () => {
  it('exposes stable public factories and typed error classes', () => {
    const required = [
      'createApiClient',
      'createParkingApi',
      'createAuthApi',
      'createUsersApi',
      'createMediaApi',
      'createIdempotencyKey',
      'IDEMPOTENCY_HEADER',
      'MemoryTokenStorage',
      'UnauthorizedError',
      'ValidationError',
      'ConflictError',
      'NotFoundError',
      'CancellationError',
      'TimeoutError',
      'NetworkError',
      'parseApiError',
      'toParkioError',
    ] as const;

    for (const name of required) {
      expect(apiClient[name], `missing export ${name}`).toBeDefined();
    }
  });

  it('serializes nearby query params and maps PublicSpot responses', async () => {
    let seenUrl = '';
    server.use(
      http.get(`${BASE}/parking/spots/nearby`, ({ request }) => {
        seenUrl = request.url;
        return HttpResponse.json([
          {
            id: 'spot-1',
            latitude: 41.01,
            longitude: 29.01,
            status: 'ACTIVE',
            expiresAt: '2026-07-24T12:00:00Z',
          },
        ]);
      }),
    );

    const client = createApiClient({
      baseURL: BASE,
      tokenStorage: new MemoryTokenStorage(),
    });
    const parking = createParkingApi(client);
    const spots = await parking.getNearbySpots({
      lat: 41.01,
      lng: 29.01,
      radius: 500,
      limit: 10,
    });

    expect(seenUrl).toContain('lat=41.01');
    expect(seenUrl).toContain('lng=29.01');
    expect(seenUrl).toContain('radius=500');
    expect(seenUrl).toContain('limit=10');
    expect(spots).toHaveLength(1);
    expect(spots[0]?.id).toBe('spot-1');
    expect(spots[0]?.status).toBe('ACTIVE');
  });

  it('propagates Authorization and Idempotency-Key on verify/claim/create', async () => {
    const seen: Array<{ path: string; auth: string | null; idem: string | null }> = [];
    server.use(
      http.post(`${BASE}/parking/spots`, async ({ request }) => {
        seen.push({
          path: '/parking/spots',
          auth: request.headers.get('authorization'),
          idem: request.headers.get(IDEMPOTENCY_HEADER),
        });
        return HttpResponse.json({ id: 'created-1', status: 'PENDING_VALIDATION' }, { status: 201 });
      }),
      http.post(`${BASE}/parking/spots/:id/verify`, ({ request, params }) => {
        seen.push({
          path: `/parking/spots/${params.id}/verify`,
          auth: request.headers.get('authorization'),
          idem: request.headers.get(IDEMPOTENCY_HEADER),
        });
        return HttpResponse.json({ id: params.id, status: 'VERIFIED' });
      }),
      http.post(`${BASE}/parking/spots/:id/claim`, ({ request, params }) => {
        seen.push({
          path: `/parking/spots/${params.id}/claim`,
          auth: request.headers.get('authorization'),
          idem: request.headers.get(IDEMPOTENCY_HEADER),
        });
        return HttpResponse.json({ id: params.id, status: 'FILLED' });
      }),
    );

    const storage = new MemoryTokenStorage();
    storage.setTokens({ accessToken: 'access-task08' });
    const parking = createParkingApi(createApiClient({ baseURL: BASE, tokenStorage: storage }));

    const createKey = createIdempotencyKey();
    await parking.createParkingSpot(
      {
        mediaId: 'media-1',
        latitude: 41.0,
        longitude: 29.0,
        description: 'task08',
        manualLocationEdited: false,
        suitableVehicleTypes: ['SEDAN'],
        parkingContext: 'STREET_PARKING',
        legalStatus: 'LEGAL',
        violationReasons: [],
      },
      createKey,
    );
    await parking.verifySpot('spot-9', { result: 'AVAILABLE' }, 'verify-key');
    await parking.claimSpot('spot-9', 'claim-key');

    expect(seen).toHaveLength(3);
    for (const entry of seen) {
      expect(entry.auth).toBe('Bearer access-task08');
      expect(entry.idem).toBeTruthy();
    }
    expect(seen[0]?.idem).toBe(createKey);
    expect(seen[1]?.idem).toBe('verify-key');
    expect(seen[2]?.idem).toBe('claim-key');
  });

  it('maps typed HTTP errors without message-string guessing', async () => {
    server.use(
      http.get(`${BASE}/parking/spots/missing`, () =>
        HttpResponse.json(
          {
            code: 'SPOT_NOT_FOUND',
            message: 'not found',
            traceId: 't-404',
            timestamp: '2026-07-24T10:00:00Z',
          },
          { status: 404 },
        ),
      ),
      http.post(`${BASE}/parking/spots/x/claim`, () =>
        HttpResponse.json(
          {
            code: 'OWNER_CANNOT_CLAIM',
            message: 'owner',
            traceId: 't-409',
            timestamp: '2026-07-24T10:00:00Z',
          },
          { status: 409 },
        ),
      ),
      http.post(`${BASE}/parking/spots`, () =>
        HttpResponse.json(
          {
            code: 'VALIDATION_FAILED',
            message: 'bad',
            traceId: 't-400',
            timestamp: '2026-07-24T10:00:00Z',
          },
          { status: 400 },
        ),
      ),
    );

    const parking = createParkingApi(
      createApiClient({ baseURL: BASE, tokenStorage: new MemoryTokenStorage() }),
    );

    await expect(parking.getSpot('missing')).rejects.toBeInstanceOf(NotFoundError);
    await expect(parking.claimSpot('x', 'k')).rejects.toBeInstanceOf(ConflictError);
    await expect(
      parking.createParkingSpot(
        {
          mediaId: 'm',
          latitude: 0,
          longitude: 0,
          description: '',
          manualLocationEdited: false,
          suitableVehicleTypes: ['SEDAN'],
          parkingContext: 'STREET_PARKING',
          legalStatus: 'LEGAL',
          violationReasons: [],
        },
        'k',
      ),
    ).rejects.toBeInstanceOf(ValidationError);
  });

  it('keeps transport error classes distinct for cancel/timeout/network', () => {
    expect(new CancellationError('aborted')).toBeInstanceOf(CancellationError);
    expect(new TimeoutError('slow')).toBeInstanceOf(TimeoutError);
    expect(new NetworkError('offline')).toBeInstanceOf(NetworkError);

    const body = {
      code: 'INVALID_TOKEN',
      message: 'x',
      traceId: 't',
      timestamp: '2026-07-24T10:00:00Z',
    };
    expect(parseApiError(body)).toEqual(body);
    expect(toParkioError(401, body)).toBeInstanceOf(UnauthorizedError);
  });
});
