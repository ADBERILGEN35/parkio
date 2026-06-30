import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { createApiClient, DEFAULT_API_BASE_URL } from './client';
import { IDEMPOTENCY_HEADER } from './idempotency';
import { createMediaApi, type MediaFilePart } from './media';
import { MemoryTokenStorage } from './token-storage';

const BASE = DEFAULT_API_BASE_URL;
const server = setupServer();

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const client = createApiClient({ tokenStorage: new MemoryTokenStorage() });
const mediaApi = createMediaApi(client);

const OK_RESPONSE = {
  mediaId: 'b7e486a0-0000-0000-0000-0000000000aa',
  status: 'PENDING',
  contentType: 'image/jpeg',
  fileSize: 11,
};

describe('mediaApi.uploadMedia', () => {
  it('posts the React Native file part as multipart with the idempotency header', async () => {
    const seen = { key: null as string | null, filename: null as string | null, type: null as string | null };
    server.use(
      http.post(`${BASE}/media/upload`, async ({ request }) => {
        seen.key = request.headers.get(IDEMPOTENCY_HEADER);
        const form = await request.formData();
        const file = form.get('file');
        if (file instanceof File) {
          seen.filename = file.name;
          seen.type = file.type;
        }
        return HttpResponse.json(OK_RESPONSE, { status: 201 });
      }),
    );

    // The RN shape ({ uri, name, type }) is what mobile sends; under msw/node it
    // is normalised into a File-like part keyed by `name`/`type`.
    const part: MediaFilePart = { uri: 'file:///tmp/spot.jpg', name: 'spot.jpg', type: 'image/jpeg' };
    const result = await mediaApi.uploadMedia(part, 'key-rn-1');

    expect(result.mediaId).toBe(OK_RESPONSE.mediaId);
    expect(seen.key).toBe('key-rn-1');
    expect(seen.filename).toBe('spot.jpg');
    expect(seen.type).toBe('image/jpeg');
  });

  it('rejects when the abort signal is already aborted (cancel support)', async () => {
    server.use(http.post(`${BASE}/media/upload`, () => HttpResponse.json(OK_RESPONSE, { status: 201 })));

    const controller = new AbortController();
    controller.abort();

    await expect(
      mediaApi.uploadMedia({ uri: 'file:///tmp/spot.jpg', name: 'spot.jpg', type: 'image/jpeg' }, 'key-rn-2', {
        signal: controller.signal,
      }),
    ).rejects.toBeTruthy();
  });
});
