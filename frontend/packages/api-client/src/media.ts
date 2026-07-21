import type { AxiosInstance, AxiosProgressEvent, GenericAbortSignal } from 'axios';
import type { ClaimedRegion, UploadMediaResponse } from '@parkio/types';
import { IDEMPOTENCY_HEADER } from './idempotency';

/**
 * React Native multipart descriptor. RN's `FormData` accepts a
 * `{ uri, name, type }` object in place of a web `File`/`Blob`; the native
 * networking layer streams the file at `uri` without ever loading the bytes
 * into JS memory. Web continues to pass a real `File`.
 */
export interface MediaFilePart {
  uri: string;
  name: string;
  type: string;
}

export type UploadMediaFile = File | MediaFilePart;

/** Per-call upload controls. Both platforms share the same implementation. */
export interface UploadMediaOptions {
  /** AbortController signal — abort it to cancel an in-flight upload. */
  signal?: GenericAbortSignal;
  /** Progress callback driven by the XHR upload events (bytes loaded / total). */
  onUploadProgress?: (event: AxiosProgressEvent) => void;
  /** Optional claimed parking region annotation (normalized [0,1]). */
  claimedRegion?: ClaimedRegion | null;
}

export function createMediaApi(client: AxiosInstance) {
  return {
    uploadMedia(
      file: UploadMediaFile,
      idempotencyKey: string,
      options?: UploadMediaOptions,
    ): Promise<UploadMediaResponse> {
      const form = new FormData();
      // Web passes a DOM `File`; React Native passes a `{ uri, name, type }`
      // part. `FormData` accepts both at runtime, but the RN shape is not
      // assignable to the DOM `FormData` typings — cast at this single boundary.
      form.append('file', file as unknown as Blob);
      const region = options?.claimedRegion;
      if (region) {
        form.append('claimedRegionX', String(region.x));
        form.append('claimedRegionY', String(region.y));
        form.append('claimedRegionWidth', String(region.width));
        form.append('claimedRegionHeight', String(region.height));
      }
      return client
        .post<UploadMediaResponse>('/media/upload', form, {
          headers: {
            [IDEMPOTENCY_HEADER]: idempotencyKey,
            'Content-Type': 'multipart/form-data',
          },
          signal: options?.signal,
          onUploadProgress: options?.onUploadProgress,
          // Multipart uploads of multi-MB photos on slow cellular links can
          // legitimately outlive the client's default 30s timeout — give them
          // their own budget instead of failing a healthy transfer mid-flight.
          timeout: 120_000,
        })
        .then((r) => r.data);
    },
  };
}

export type MediaApi = ReturnType<typeof createMediaApi>;
