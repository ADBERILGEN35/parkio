import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { createApiClient, DEFAULT_API_BASE_URL } from './client';
import { IDEMPOTENCY_HEADER, createIdempotencyKey } from './idempotency';
import { createMediaApi } from './media';
import { createParkingApi } from './parking';
import { MemoryTokenStorage } from './token-storage';

const BASE = DEFAULT_API_BASE_URL;

const server = setupServer();

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const client = createApiClient({ tokenStorage: new MemoryTokenStorage() });
const parkingApi = createParkingApi(client);
const mediaApi = createMediaApi(client);

function capture(method: typeof http.post, url: string, seen: { key: string | null }) {
  return method(url, ({ request }) => {
    seen.key = request.headers.get(IDEMPOTENCY_HEADER);
    return HttpResponse.json({});
  });
}

describe('Idempotency-Key header', () => {
  it('createIdempotencyKey returns fresh UUIDs', () => {
    const a = createIdempotencyKey();
    const b = createIdempotencyKey();
    expect(a).toMatch(/^[0-9a-f-]{36}$/);
    expect(a).not.toBe(b);
  });

  it('is sent by createParkingSpot', async () => {
    const seen = { key: null as string | null };
    server.use(capture(http.post, `${BASE}/parking/spots`, seen));

    await parkingApi.createParkingSpot(
      {
        mediaId: 'b7e486a0-0000-0000-0000-000000000001',
        latitude: 41.0,
        longitude: 29.0,
        suitableVehicleTypes: ['SEDAN'],
        parkingContext: 'STREET_PARKING',
        legalStatus: 'LEGAL',
      },
      'key-create-1',
    );

    expect(seen.key).toBe('key-create-1');
  });

  it('is sent by verifySpot', async () => {
    const seen = { key: null as string | null };
    server.use(capture(http.post, `${BASE}/parking/spots/spot-1/verify`, seen));

    await parkingApi.verifySpot('spot-1', { result: 'AVAILABLE' }, 'key-verify-1');

    expect(seen.key).toBe('key-verify-1');
  });

  it('is sent by claimSpot', async () => {
    const seen = { key: null as string | null };
    server.use(capture(http.post, `${BASE}/parking/spots/spot-1/claim`, seen));

    await parkingApi.claimSpot('spot-1', 'key-claim-1');

    expect(seen.key).toBe('key-claim-1');
  });

  it('is sent by uploadMedia', async () => {
    const seen = { key: null as string | null };
    server.use(capture(http.post, `${BASE}/media/upload`, seen));

    const file = new File(['photo-bytes'], 'spot.jpg', { type: 'image/jpeg' });
    await mediaApi.uploadMedia(file, 'key-upload-1');

    expect(seen.key).toBe('key-upload-1');
  });
});
