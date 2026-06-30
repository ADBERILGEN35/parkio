import { createIdempotencyKey, type MediaFilePart } from '@parkio/api-client';
import type { UploadMediaResponse } from '@parkio/types';
import { useCallback, useEffect, useRef, useState } from 'react';
import { toUserMessage } from '@/utils/errors';
import { mediaApi } from '@/services/media';
import { deleteTempFiles } from '../lib/fileSystem';
import { prepareImage } from '../lib/prepareImage';
import { isWithinUploadSize } from '../lib/validation';
import type { LocalAsset, PreparedImage, UploadPhase } from '../types';

export interface UseMediaUpload {
  phase: UploadPhase;
  /** 0–1 fraction of bytes uploaded. */
  progress: number;
  error?: string;
  response?: UploadMediaResponse;
  /** Prepare + upload a freshly-confirmed asset (fresh idempotency key). */
  start: (asset: LocalAsset) => void;
  /** Abort an in-flight upload. */
  cancel: () => void;
  /** Re-attempt after a failure, reusing the same idempotency key (safe retry). */
  retry: () => void;
  /** Return to idle and clean up any temp files. */
  reset: () => void;
}

/**
 * Orchestrates the prepare → upload pipeline through the shared
 * {@link mediaApi.uploadMedia}. Owns:
 *  - **progress** via axios `onUploadProgress`,
 *  - **cancel** via an `AbortController` whose signal is passed to the request,
 *  - **retry** that reuses the same `Idempotency-Key` and the already-prepared
 *    bytes, so the backend dedupes instead of storing a duplicate,
 *  - **temp-file cleanup** on every terminal state and on unmount.
 *
 * Network interruptions surface as a normal upload error (phase `error`); the
 * UI gates Retry on connectivity. Preparation runs once per asset and is reused
 * across retries.
 */
export function useMediaUpload(): UseMediaUpload {
  const [phase, setPhase] = useState<UploadPhase>('idle');
  const [progress, setProgress] = useState(0);
  const [error, setError] = useState<string | undefined>();
  const [response, setResponse] = useState<UploadMediaResponse | undefined>();

  // Pipeline identity, kept in refs so retries reuse them without re-rendering.
  const idempotencyKeyRef = useRef<string | null>(null);
  const sourceAssetRef = useRef<LocalAsset | null>(null);
  const preparedRef = useRef<PreparedImage | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  const tempUrisRef = useRef<Set<string>>(new Set());
  // Guards against state updates after unmount.
  const mountedRef = useRef(true);

  const trackTemp = useCallback((uri: string) => {
    tempUrisRef.current.add(uri);
  }, []);

  const purgeTemps = useCallback(() => {
    deleteTempFiles(tempUrisRef.current);
    tempUrisRef.current.clear();
  }, []);

  const run = useCallback(async () => {
    const asset = sourceAssetRef.current;
    const key = idempotencyKeyRef.current;
    if (!asset || !key) return;

    setError(undefined);

    try {
      // Prepare once; reuse the result across retries.
      let prepared = preparedRef.current;
      if (!prepared) {
        if (mountedRef.current) setPhase('preparing');
        prepared = await prepareImage(asset);
        preparedRef.current = prepared;
        trackTemp(prepared.uri);
        if (!isWithinUploadSize(prepared.fileSize)) {
          throw new Error('We couldn’t compress that photo enough to upload. Try a different one.');
        }
      }

      const controller = new AbortController();
      abortRef.current = controller;
      if (mountedRef.current) {
        setPhase('uploading');
        setProgress(0);
      }

      const part: MediaFilePart = { uri: prepared.uri, name: prepared.name, type: prepared.contentType };
      const result = await mediaApi.uploadMedia(part, key, {
        signal: controller.signal,
        onUploadProgress: (event) => {
          const fraction = event.total ? event.loaded / event.total : (event.progress ?? 0);
          if (mountedRef.current) setProgress(Math.min(Math.max(fraction, 0), 1));
        },
      });

      if (!mountedRef.current) return;
      setResponse(result);
      setProgress(1);
      setPhase('success');
      // Success is terminal — the prepared + source temp files can go now.
      purgeTemps();
      if (asset.temporary) {
        deleteTempFiles([asset.uri]);
      }
    } catch (err) {
      if (!mountedRef.current) return;
      if (abortRef.current?.signal.aborted) {
        setPhase('cancelled');
        return;
      }
      setError(toUserMessage(err));
      setPhase('error');
    } finally {
      abortRef.current = null;
    }
  }, [purgeTemps, trackTemp]);

  const start = useCallback(
    (asset: LocalAsset) => {
      idempotencyKeyRef.current = createIdempotencyKey();
      sourceAssetRef.current = asset;
      preparedRef.current = null;
      setResponse(undefined);
      void run();
    },
    [run],
  );

  const retry = useCallback(() => {
    // Same key + same prepared bytes → backend treats it as the same request.
    void run();
  }, [run]);

  const cancel = useCallback(() => {
    abortRef.current?.abort();
  }, []);

  const reset = useCallback(() => {
    abortRef.current?.abort();
    purgeTemps();
    if (sourceAssetRef.current?.temporary) {
      deleteTempFiles([sourceAssetRef.current.uri]);
    }
    idempotencyKeyRef.current = null;
    sourceAssetRef.current = null;
    preparedRef.current = null;
    setPhase('idle');
    setProgress(0);
    setError(undefined);
    setResponse(undefined);
  }, [purgeTemps]);

  useEffect(() => {
    mountedRef.current = true;
    const tempUris = tempUrisRef.current;
    return () => {
      mountedRef.current = false;
      abortRef.current?.abort();
      deleteTempFiles(tempUris);
      tempUris.clear();
    };
  }, []);

  return { phase, progress, error, response, start, cancel, retry, reset };
}
