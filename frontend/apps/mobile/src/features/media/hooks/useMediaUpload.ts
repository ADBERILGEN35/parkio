import { createIdempotencyKey, type MediaFilePart } from '@parkio/api-client';
import type { UploadMediaResponse } from '@parkio/types';
import { useCallback, useEffect, useRef, useState } from 'react';
import { toUserMessage } from '@/utils/errors';
import { track } from '@/services/analytics';
import { mediaApi } from '@/services/media';
import { deleteTempFiles } from '../lib/fileSystem';
import { prepareImage } from '../lib/prepareImage';
import { isWithinUploadSize } from '../lib/validation';
import type { LocalAsset, PreparedImage, UploadPhase } from '../types';

/** Progress state updates are skipped below this delta to avoid re-render storms. */
const PROGRESS_EPSILON = 0.01;

export interface UseMediaUpload {
  phase: UploadPhase;
  /** 0–1 fraction of bytes uploaded. */
  progress: number;
  error?: string;
  response?: UploadMediaResponse;
  /** Prepare + upload a freshly-confirmed asset (fresh idempotency key). */
  start: (asset: LocalAsset) => void;
  /** Abort an in-flight upload (also honored mid-preparation). */
  cancel: () => void;
  /** Re-attempt after a failure/cancel, reusing the same idempotency key (safe retry). */
  retry: () => void;
  /** Return to idle and clean up any temp files (including the source capture). */
  reset: () => void;
}

/**
 * Orchestrates the prepare → upload pipeline through the shared
 * {@link mediaApi.uploadMedia}. Owns:
 *  - **progress** via axios `onUploadProgress` (thresholded to ≥1% steps),
 *  - **cancel** via an `AbortController` plus a cancel-requested flag so a
 *    cancel tapped during *preparation* also lands (the HTTP request is never
 *    fired) instead of being a no-op,
 *  - **retry** that reuses the same `Idempotency-Key` and the already-prepared
 *    bytes, so the backend dedupes instead of storing a duplicate,
 *  - **single-flight**: `start`/`retry` are ignored while a run is in flight
 *    (a double-tap can never race two requests or duplicate an upload), and a
 *    `reset` bumps the pipeline generation so a superseded run can no longer
 *    touch state or temp tracking,
 *  - **temp-file lifecycle**: prepared encodes are purged on success/reset/
 *    unmount. The *source* asset is only deleted on `reset` or on unmount
 *    without a successful upload — after success its uri is handed to the
 *    spot-creation draft as the preview, and the draft flow owns deleting it.
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
  // Cancel tapped while preparing (no request to abort yet) or uploading.
  const cancelRequestedRef = useRef(false);
  // True while a run() is executing — overlapping start/retry calls are ignored.
  const inFlightRef = useRef(false);
  // Bumped by reset(): a run started under an older generation must not touch
  // state (fixes the race where an aborted run's catch fired *after* reset had
  // already returned the UI to idle, leaving it stuck on "cancelled").
  const generationRef = useRef(0);
  // Set on success so unmount cleanup knows the source uri was handed off.
  const handedOffRef = useRef(false);
  // Guards against state updates after unmount.
  const mountedRef = useRef(true);
  const lastProgressRef = useRef(0);

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
    // Single-flight: the UI disables re-entry, but enforce it here so a stray
    // double-tap can never race two requests (or two idempotency keys).
    if (inFlightRef.current) return;
    inFlightRef.current = true;
    const generation = generationRef.current;
    // False once reset() has superseded this run — every state update and ref
    // write below must check it.
    const live = () => generationRef.current === generation && mountedRef.current;

    if (live()) setError(undefined);

    try {
      // Prepare once; reuse the result across retries.
      let prepared = preparedRef.current;
      if (!prepared) {
        if (live()) setPhase('preparing');
        const fresh = await prepareImage(asset);
        if (generationRef.current !== generation) {
          // Superseded mid-prepare: this file is untracked by the new
          // generation, so it must be deleted here or it leaks.
          deleteTempFiles([fresh.uri]);
          return;
        }
        prepared = fresh;
        preparedRef.current = fresh;
        trackTemp(fresh.uri);
        if (!isWithinUploadSize(fresh.fileSize)) {
          throw new Error('We couldn’t compress that photo enough to upload. Try a different one.');
        }
      }

      // Cancel arrived while preparing → stop before any bytes leave the
      // device. The prepared file is kept so Retry skips re-preparation.
      if (cancelRequestedRef.current) {
        if (live()) setPhase('cancelled');
        return;
      }

      const controller = new AbortController();
      abortRef.current = controller;
      lastProgressRef.current = 0;
      if (live()) {
        setPhase('uploading');
        setProgress(0);
      }

      const part: MediaFilePart = { uri: prepared.uri, name: prepared.name, type: prepared.contentType };
      const result = await mediaApi.uploadMedia(part, key, {
        signal: controller.signal,
        onUploadProgress: (event) => {
          const fraction = event.total ? event.loaded / event.total : (event.progress ?? 0);
          const clamped = Math.min(Math.max(fraction, 0), 1);
          if (!live()) return;
          if (clamped >= 1 || clamped - lastProgressRef.current >= PROGRESS_EPSILON) {
            lastProgressRef.current = clamped;
            setProgress(clamped);
          }
        },
      });

      if (generationRef.current !== generation) return;
      handedOffRef.current = true;
      if (!live()) return;
      setResponse(result);
      setProgress(1);
      setPhase('success');
      track('upload_completed');
      // Success is terminal for the *prepared* temp files. The source asset is
      // NOT deleted here: its uri becomes the spot-creation draft preview and
      // the draft flow deletes it when the draft is cleared.
      purgeTemps();
    } catch (err) {
      if (!live()) return;
      if (cancelRequestedRef.current || abortRef.current?.signal.aborted) {
        setPhase('cancelled');
        return;
      }
      setError(toUserMessage(err));
      setPhase('error');
    } finally {
      if (generationRef.current === generation) {
        abortRef.current = null;
      }
      inFlightRef.current = false;
    }
  }, [purgeTemps, trackTemp]);

  const start = useCallback(
    (asset: LocalAsset) => {
      // A tap that races an in-flight pipeline is dropped, not queued — the
      // first pipeline keeps running and the UI keeps reflecting it.
      if (inFlightRef.current) return;
      track('upload_started');
      cancelRequestedRef.current = false;
      handedOffRef.current = false;
      idempotencyKeyRef.current = createIdempotencyKey();
      sourceAssetRef.current = asset;
      preparedRef.current = null;
      setResponse(undefined);
      void run();
    },
    [run],
  );

  const retry = useCallback(() => {
    if (inFlightRef.current) return;
    // Same key + same prepared bytes → backend treats it as the same request.
    cancelRequestedRef.current = false;
    void run();
  }, [run]);

  const cancel = useCallback(() => {
    track('upload_cancelled');
    cancelRequestedRef.current = true;
    abortRef.current?.abort();
  }, []);

  const reset = useCallback(() => {
    // Supersede any in-flight run *before* aborting so its rejection handler
    // sees a stale generation and leaves the fresh idle state alone.
    generationRef.current += 1;
    abortRef.current?.abort();
    abortRef.current = null;
    purgeTemps();
    // After a successful handoff the source uri lives on as the spot-creation
    // draft preview — the draft flow deletes it, not us.
    if (!handedOffRef.current && sourceAssetRef.current?.temporary) {
      deleteTempFiles([sourceAssetRef.current.uri]);
    }
    cancelRequestedRef.current = false;
    handedOffRef.current = false;
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
      // Abandoned mid-flow (no successful handoff): the temporary source
      // capture is ours to clean. After success the draft flow owns it.
      const source = sourceAssetRef.current;
      if (!handedOffRef.current && source?.temporary) {
        deleteTempFiles([source.uri]);
      }
    };
  }, []);

  return { phase, progress, error, response, start, cancel, retry, reset };
}
