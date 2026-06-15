import { useCallback, useEffect, useRef, useState } from 'react';
import { geocodePlaces, type GeocodeResult } from './geocoding';

/** Minimum characters before typeahead fires (avoids noisy 1–2 char lookups). */
export const AUTOCOMPLETE_MIN_CHARS = 3;

/** Debounce window for typeahead requests — keeps Nominatim usage polite. */
export const AUTOCOMPLETE_DEBOUNCE_MS = 350;

export type AutocompleteStatus = 'idle' | 'loading' | 'success' | 'error';

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
 * Typeahead place search over the Nominatim geocoder.
 *
 * Responsibilities:
 * - Debounce keystrokes ({@link AUTOCOMPLETE_DEBOUNCE_MS}) and only fire at/above
 *   {@link AUTOCOMPLETE_MIN_CHARS}.
 * - Ignore stale responses so a slow earlier query never overwrites a newer one.
 *
 * Staleness is enforced with a monotonic request id rather than an
 * `AbortController`: aborting the underlying `fetch` is unnecessary for this
 * local-beta typeahead, and a plain id guard avoids the cross-realm
 * `AbortSignal` mismatch in the jsdom + undici test environment. The dropped
 * response is simply not applied.
 *
 * Local-beta only: production should move geocoding behind the backend / an SLA
 * provider (see `geocoding.ts`).
 */
export function usePlaceAutocomplete(): PlaceAutocomplete {
  const [status, setStatus] = useState<AutocompleteStatus>('idle');
  const [results, setResults] = useState<GeocodeResult[]>([]);

  const debounceRef = useRef<ReturnType<typeof setTimeout>>();
  const requestIdRef = useRef(0);

  const cancelPending = useCallback(() => {
    if (debounceRef.current) {
      clearTimeout(debounceRef.current);
      debounceRef.current = undefined;
    }
    // Invalidate any in-flight request so its late response is ignored.
    requestIdRef.current += 1;
  }, []);

  const clear = useCallback(() => {
    cancelPending();
    setStatus('idle');
    setResults([]);
  }, [cancelPending]);

  const runRequest = useCallback((query: string): Promise<GeocodeResult[]> => {
    const requestId = (requestIdRef.current += 1);
    setStatus('loading');
    return geocodePlaces(query)
      .then((places) => {
        if (requestId !== requestIdRef.current) return [];
        setResults(places);
        setStatus('success');
        return places;
      })
      .catch(() => {
        if (requestId !== requestIdRef.current) return [];
        setResults([]);
        setStatus('error');
        return [];
      });
  }, []);

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
      }, AUTOCOMPLETE_DEBOUNCE_MS);
    },
    [cancelPending, runRequest],
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
      if (debounceRef.current) clearTimeout(debounceRef.current);
    };
  }, []);

  return { status, results, suggest, flush, clear };
}
