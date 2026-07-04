import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
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
  it('posts a web File as multipart with the idempotency header', async () => {
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

    const file = new File([Uint8Array.from([1, 2, 3])], 'spot.jpg', { type: 'image/jpeg' });
    const result = await mediaApi.uploadMedia(file, 'key-web-1');

    expect(result.mediaId).toBe(OK_RESPONSE.mediaId);
    expect(seen.key).toBe('key-web-1');
    expect(seen.filename).toBe('spot.jpg');
    expect(seen.type).toBe('image/jpeg');
  });

  it('appends the React Native file part with name and type for native FormData', async () => {
    // Node's undici FormData cannot simulate RN's `{ uri, name, type }` part
    // serialization. Capture append() to assert the client contract mobile relies on.
    type AppendCall = { name: string; value: unknown };
    const appended: AppendCall[] = [];
    const OriginalFormData = globalThis.FormData;

    class CapturingFormData extends OriginalFormData {
      override append(name: string, value: string | Blob, fileName?: string): void {
        appended.push({ name, value });
        if (fileName !== undefined) {
          super.append(name, value as Blob, fileName);
          return;
        }
        super.append(name, value as never);
      }
    }

    vi.stubGlobal('FormData', CapturingFormData);
    server.use(
      http.post(`${BASE}/media/upload`, () => HttpResponse.json(OK_RESPONSE, { status: 201 })),
    );

    try {
      const part: MediaFilePart = { uri: 'file:///tmp/spot.jpg', name: 'spot.jpg', type: 'image/jpeg' };
      const result = await mediaApi.uploadMedia(part, 'key-rn-1');

      expect(result.mediaId).toBe(OK_RESPONSE.mediaId);
      const filePart = appended.find((entry) => entry.name === 'file');
      expect(filePart).toBeDefined();
      expect(filePart?.value).toEqual(part);
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it('sends the idempotency header for React Native uploads', async () => {
    const seen = { key: null as string | null };
    server.use(
      http.post(`${BASE}/media/upload`, ({ request }) => {
        seen.key = request.headers.get(IDEMPOTENCY_HEADER);
        return HttpResponse.json(OK_RESPONSE, { status: 201 });
      }),
    );

    await mediaApi.uploadMedia(
      { uri: 'file:///tmp/spot.jpg', name: 'spot.jpg', type: 'image/jpeg' },
      'key-rn-header',
    );

    expect(seen.key).toBe('key-rn-header');
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
