import { useCallback, useEffect, useRef } from 'react';
import { createIdempotencyKey, type MediaFilePart } from '@parkio/api-client';
import { isValidClaimedRegion } from '@parkio/types';
import { useOnlineStatus } from '@/hooks/useOnlineStatus';
import { mediaApi } from '@/services/api';
import { useShareDraftStore } from './state/shareDraftStore';

/**
 * Background upload for the share draft: starts only after a valid claimed
 * region is set, reports progress into the store, survives step navigation,
 * queues offline (auto-retries on reconnect), and reuses one idempotency key
 * per photo so retries dedupe server-side instead of duplicating uploads.
 */
export function useDraftUpload() {
  const online = useOnlineStatus();
  const onlineRef = useRef(online);
  useEffect(() => {
    onlineRef.current = online;
  }, [online]);

  const photo = useShareDraftStore((s) => s.photo);
  const mediaId = useShareDraftStore((s) => s.mediaId);
  const phase = useShareDraftStore((s) => s.uploadPhase);
  const hasRegion = isValidClaimedRegion(photo?.claimedRegion);

  const abortRef = useRef<AbortController | null>(null);
  const keyByUriRef = useRef<{ uri: string; key: string } | null>(null);
  const inFlightRef = useRef(false);

  const run = useCallback(async () => {
    const state = useShareDraftStore.getState();
    const currentPhoto = state.photo;
    const region = currentPhoto?.claimedRegion ?? null;
    if (!currentPhoto || !isValidClaimedRegion(region) || state.mediaId || inFlightRef.current) {
      return;
    }
    if (!onlineRef.current) {
      state.setUpload('offline');
      return;
    }

    // One idempotency key per photo: retries dedupe server-side.
    if (keyByUriRef.current?.uri !== currentPhoto.uri) {
      keyByUriRef.current = { uri: currentPhoto.uri, key: createIdempotencyKey() };
    }

    const filePart: MediaFilePart = {
      uri: currentPhoto.uri,
      name: 'spot.jpg',
      type: 'image/jpeg',
    };

    inFlightRef.current = true;
    const abort = new AbortController();
    abortRef.current = abort;
    state.setUpload('uploading', 0);
    try {
      const response = await mediaApi.uploadMedia(filePart, keyByUriRef.current.key, {
        signal: abort.signal,
        claimedRegion: region,
        onUploadProgress: (event) => {
          const total = event.total ?? 0;
          if (total > 0) {
            useShareDraftStore.getState().setUpload('uploading', event.loaded / total);
          }
        },
      });
      const store = useShareDraftStore.getState();
      // Photo replaced mid-flight → discard the stale result.
      if (store.photo?.uri !== currentPhoto.uri) {
        return;
      }
      store.setMediaId(response.mediaId);
      store.setUpload(response.status === 'READY' ? 'ready' : 'scanning', 1);
    } catch (error) {
      const store = useShareDraftStore.getState();
      if (store.photo?.uri !== currentPhoto.uri) {
        return;
      }
      if (abort.signal.aborted) {
        store.setUpload('idle', 0);
      } else if (!onlineRef.current) {
        store.setUpload('offline');
      } else {
        console.warn('[share] upload failed', error);
        store.setUpload('failed');
      }
    } finally {
      inFlightRef.current = false;
      abortRef.current = null;
    }
  }, []);

  // Auto-start when a photo + claimed region land or connectivity returns.
  useEffect(() => {
    if (!photo || !hasRegion || mediaId) {
      return;
    }
    if (online && (phase === 'idle' || phase === 'offline')) {
      void run();
    } else if (!online && phase === 'idle') {
      useShareDraftStore.getState().setUpload('offline');
    }
  }, [photo, hasRegion, mediaId, phase, online, run]);

  const cancel = useCallback(() => {
    abortRef.current?.abort();
  }, []);

  const retry = useCallback(() => {
    if (!inFlightRef.current) {
      useShareDraftStore.getState().setUpload('idle', 0);
      void run();
    }
  }, [run]);

  return { cancel, retry };
}
