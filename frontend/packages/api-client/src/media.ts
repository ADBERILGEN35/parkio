import type { AxiosInstance } from 'axios';
import type { UploadMediaResponse } from '@parkio/types';
import { IDEMPOTENCY_HEADER } from './idempotency';

export function createMediaApi(client: AxiosInstance) {
  return {
    uploadMedia(file: File, idempotencyKey: string): Promise<UploadMediaResponse> {
      const form = new FormData();
      form.append('file', file);
      return client
        .post<UploadMediaResponse>('/media/upload', form, {
          headers: {
            [IDEMPOTENCY_HEADER]: idempotencyKey,
            'Content-Type': 'multipart/form-data',
          },
        })
        .then((r) => r.data);
    },
  };
}

export type MediaApi = ReturnType<typeof createMediaApi>;
