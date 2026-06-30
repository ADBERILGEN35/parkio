import { createMediaApi } from '@parkio/api-client';
import { apiClient } from './api';

/**
 * Single media-API instance for the app. Built on the same authenticated
 * {@link apiClient} as every other service, so JWT auth, the single-flight
 * refresh, and correlation headers all apply to uploads with zero extra wiring.
 * We reuse the shared `@parkio/api-client` upload verbatim — no upload logic is
 * re-implemented on mobile (see `packages/api-client/src/media.ts`).
 */
export const mediaApi = createMediaApi(apiClient);
