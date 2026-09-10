import { useCallback, useEffect, useRef, useState } from 'react';
import { RateLimitError } from '@parkio/api-client';
import { useParkioSdk } from '@/app/AppRuntimeContext';
import { geocodePlaces, type GeocodeResult } from './geocoding';

/** Minimum characters before typeahead fires (avoids noisy 1–2 char lookups). */
export const AUTOCOMPLETE_MIN_CHARS = 3;

/** Debounce window for typeahead requests — keeps backend/provider usage polite. */
export const AUTOCOMPLETE_DEBOUNCE_MS = 350;

/**
 * Slightly calmer debounce for the anonymous public geocoding tier
 * (1/s replenish, burst 5). Still cancellation-first; does not globally change /map.
 */
export const PUBLIC_AUTOCOMPLETE_DEBOUNCE_MS = 400;

/** One fast retry smooths over transient gateway/network failures without hiding real outages. */
const AUTOCOMPLETE_RETRY_ATTEMPTS = 1;
const AUTOCOMPLETE_RETRY_DELAY_MS = 150;

export type AutocompleteStatus = 'idle' | 'loading' | 'success' | 'error' | 'rateLimited';

export type PlaceSearchFn = (query: string, signal: AbortSignal) => Promise<GeocodeResult[]>;

export interface PlaceAutocompleteOptions {
  /**
   * Override the geocoding lookup. Defaults to authenticated
   * {@code GET /api/v1/geocoding/search}. Public Explore injects the certified
   * {@code GET /api/v1/public/geocoding/search} client.
   */
  searchFn?: PlaceSearchFn;
  /** Debounce override; omit to keep {@link AUTOCOMPLETE_DEBOUNCE_MS}. */
  debounceMs?: number;
  /**
   * When false, never retry failed lookups (preferred for anonymous public
   * rate-limited geocoding). Defaults to true for authenticated search.
   */
  retry?: boolean;
}

export interface PlaceAutocomplete {
  status: AutocompleteStatus;
  results: GeocodeResult[];
  /** Debounced typeahead lookup; clears when below {@link AUTOCOMPLETE_MIN_CHARS}. */
  suggest: (query: string) => void;
  /** Immediate (non-debounced) lookup for explicit submit; resolves with results. */
  flush: (query: string) => Promise<GeocodeResult[]>;
  /** Clear results and cancel any pending/in-flight request (selection, Escape). */
  clear: () => void;
}

/**
 * Typeahead place search over Parkio's backend geocoding endpoint.
 *
 * Responsibilities:
 * - Debounce keystrokes and only fire at/above {@link AUTOCOMPLETE_MIN_CHARS}.
 * - Cancel pending debounce timers and in-flight requests when the query changes,
 *   a suggestion is selected, Escape closes the menu, or the component unmounts.
 * - Ignore stale responses so a slow earlier request never overwrites a newer one.
 * - Retry one transient failure by default; never retry 429; aborts are cancellation.
 */
export function usePlaceAutocomplete(options: PlaceAutocompleteOptions = {}): PlaceAutocomplete {
  const { geocodingApi } = useParkioSdk();
  const searchFn = options.searchFn;
  const debounceMs = options.debounceMs ?? AUTOCOMPLETE_DEBOUNCE_MS;
  const retryEnabled = options.retry !== false;
  const [status, setStatus] = useState<AutocompleteStatus>('idle');
  const [results, setResults] = useState<GeocodeResult[]>([]);

  const debounceRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const abortRef = useRef<AbortController | null>(null);
  const requestIdRef = useRef(0);

  const cancelPending = useCallback(() => {
    if (debounceRef.current) {
      clearTimeout(debounceRef.current);
      debounceRef.current = undefined;
    }
    abortRef.current?.abort();
    abortRef.current = null;
    // Invalidate any in-flight request in case a transport ignores abort.
    requestIdRef.current += 1;
  }, []);

  const clear = useCallback(() => {
    cancelPending();
    setStatus('idle');
    setResults([]);
  }, [cancelPending]);

  const runRequest = useCallback(
    (query: string): Promise<GeocodeResult[]> => {
      const requestId = (requestIdRef.current += 1);
      const controller = new AbortController();
      abortRef.current = controller;

      setStatus('loading');

      const lookup: PlaceSearchFn =
        searchFn ?? ((q, signal) => geocodePlaces(geocodingApi, q, signal));

      return runGeocodeWithOptionalRetry(lookup, query, controller.signal, retryEnabled)
        .then((places) => {
          if (requestId !== requestIdRef.current) return [];
          abortRef.current = null;
          setResults(places);
          setStatus('success');
          return places;
        })
        .catch((error: unknown) => {
          if (requestId !== requestIdRef.current) return [];
          abortRef.current = null;
          if (isAbortError(error)) return [];
          setResults([]);
          setStatus(isRateLimited(error) ? 'rateLimited' : 'error');
          return [];
        });
    },
    [geocodingApi, retryEnabled, searchFn],
  );

  const suggest = useCallback(
    (raw: string) => {
      const query = raw.trim();
      cancelPending();
      if (query.length < AUTOCOMPLETE_MIN_CHARS) {
        setStatus('idle');
        setResults([]);
        return;
      }
      // Show loading immediately so the dropdown reacts before the debounce fires.
      setStatus('loading');
      debounceRef.current = setTimeout(() => {
        void runRequest(query);
      }, debounceMs);
    },
    [cancelPending, debounceMs, runRequest],
  );

  const flush = useCallback(
    (raw: string): Promise<GeocodeResult[]> => {
      const query = raw.trim();
      cancelPending();
      if (!query) {
        setStatus('idle');
        setResults([]);
        return Promise.resolve([]);
      }
      return runRequest(query);
    },
    [cancelPending, runRequest],
  );

  useEffect(() => {
    return () => {
      cancelPending();
    };
  }, [cancelPending]);

  return { status, results, suggest, flush, clear };
}

async function runGeocodeWithOptionalRetry(
  searchFn: PlaceSearchFn,
  query: string,
  signal: AbortSignal,
  retryEnabled: boolean,
): Promise<GeocodeResult[]> {
  const attempts = retryEnabled ? AUTOCOMPLETE_RETRY_ATTEMPTS : 0;
  let lastError: unknown;

  for (let attempt = 0; attempt <= attempts; attempt += 1) {
    try {
      return await searchFn(query, signal);
    } catch (error) {
      if (isAbortError(error) || signal.aborted) throw error;
      // Never retry rate limits — wait for the next user-driven search.
      if (isRateLimited(error)) throw error;
      lastError = error;
      if (attempt < attempts) {
        await abortableDelay(AUTOCOMPLETE_RETRY_DELAY_MS, signal);
      }
    }
  }

  throw lastError;
}

function isRateLimited(error: unknown): boolean {
  return error instanceof RateLimitError;
}

function abortableDelay(ms: number, signal: AbortSignal): Promise<void> {
  if (signal.aborted) return Promise.reject(createAbortError());

  return new Promise((resolve, reject) => {
    const timer = setTimeout(resolve, ms);
    signal.addEventListener(
      'abort',
      () => {
        clearTimeout(timer);
        reject(createAbortError());
      },
      { once: true },
    );
  });
}

function createAbortError(): DOMException | Error {
  if (typeof DOMException !== 'undefined') {
    return new DOMException('The operation was aborted.', 'AbortError');
  }
  const error = new Error('The operation was aborted.');
  error.name = 'AbortError';
  return error;
}

function isAbortError(error: unknown): boolean {
  if (!(error instanceof Error)) return false;
  return (
    error.name === 'AbortError' ||
    error.name === 'CanceledError' ||
    ('code' in error && error.code === 'ERR_CANCELED')
  );
}
