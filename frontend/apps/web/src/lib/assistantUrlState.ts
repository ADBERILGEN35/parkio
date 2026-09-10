import type { Destination, DestinationSource } from '@parkio/types';

/**
 * URL-safe assistant destination identity (WP-SPA-08).
 * Coexists with map discovery params; never encodes recommendation payloads.
 */

export const ASSISTANT_QUERY_KEYS = {
  destLat: 'destLat',
  destLng: 'destLng',
  destLabel: 'destLabel',
  destSource: 'destSource',
  destProvider: 'destProvider',
  destPlaceId: 'destPlaceId',
  destSubtitle: 'destSubtitle',
  candidate: 'candidate',
} as const;

const MANAGED = new Set<string>(Object.values(ASSISTANT_QUERY_KEYS));

const SOURCES: readonly DestinationSource[] = ['GEOCODING', 'MAP_PIN', 'SYSTEM'];

export type AssistantUrlState = {
  destination: Destination | null;
  candidateId: string | null;
};

export const EMPTY_ASSISTANT_URL_STATE: AssistantUrlState = {
  destination: null,
  candidateId: null,
};

function lastValue(values: readonly string[]): string | null {
  for (let i = values.length - 1; i >= 0; i -= 1) {
    const v = values[i]?.trim() ?? '';
    if (v.length > 0) return v;
  }
  return null;
}

function parseCoord(raw: string | null): number | null {
  if (raw == null || raw.trim() === '') return null;
  const n = Number(raw);
  if (!Number.isFinite(n)) return null;
  return n;
}

function parseSource(raw: string | null): DestinationSource | null {
  if (!raw) return null;
  return SOURCES.includes(raw as DestinationSource) ? (raw as DestinationSource) : null;
}

function unmanagedEntries(searchParams: URLSearchParams): Array<[string, string]> {
  return Array.from(searchParams.entries()).filter(([key]) => !MANAGED.has(key));
}

/**
 * Parse assistant destination from URL. Malformed coords → empty assistant state.
 * When assistantEnabled is false, always returns empty (params ignored).
 */
export function parseAssistantUrlState(
  searchParams: URLSearchParams,
  { assistantEnabled = true }: { assistantEnabled?: boolean } = {},
): AssistantUrlState {
  if (!assistantEnabled) {
    return EMPTY_ASSISTANT_URL_STATE;
  }

  const lat = parseCoord(lastValue(searchParams.getAll(ASSISTANT_QUERY_KEYS.destLat)));
  const lng = parseCoord(lastValue(searchParams.getAll(ASSISTANT_QUERY_KEYS.destLng)));
  const label = lastValue(searchParams.getAll(ASSISTANT_QUERY_KEYS.destLabel));

  if (lat == null || lng == null || lat < -90 || lat > 90 || lng < -180 || lng > 180 || !label) {
    return {
      destination: null,
      candidateId: lastValue(searchParams.getAll(ASSISTANT_QUERY_KEYS.candidate)),
    };
  }

  const source = parseSource(lastValue(searchParams.getAll(ASSISTANT_QUERY_KEYS.destSource)));
  const provider = lastValue(searchParams.getAll(ASSISTANT_QUERY_KEYS.destProvider));
  const providerPlaceId = lastValue(searchParams.getAll(ASSISTANT_QUERY_KEYS.destPlaceId));
  const subtitle = lastValue(searchParams.getAll(ASSISTANT_QUERY_KEYS.destSubtitle));

  const destination: Destination = {
    label,
    latitude: lat,
    longitude: lng,
    source: source ?? 'GEOCODING',
    placeIdentity:
      provider && providerPlaceId
        ? {
            provider,
            providerPlaceId,
            canonicalKey: `${provider}:${providerPlaceId}`,
          }
        : null,
    subtitle: subtitle ?? null,
  };

  return {
    destination,
    candidateId: lastValue(searchParams.getAll(ASSISTANT_QUERY_KEYS.candidate)),
  };
}

export function serializeAssistantUrlState(
  searchParams: URLSearchParams,
  state: AssistantUrlState,
  { assistantEnabled = true }: { assistantEnabled?: boolean } = {},
): URLSearchParams {
  const next = new URLSearchParams();
  for (const [key, value] of unmanagedEntries(searchParams)) {
    next.append(key, value);
  }

  if (!assistantEnabled || !state.destination) {
    return next;
  }

  const d = state.destination;
  next.set(ASSISTANT_QUERY_KEYS.destLat, String(d.latitude));
  next.set(ASSISTANT_QUERY_KEYS.destLng, String(d.longitude));
  next.set(ASSISTANT_QUERY_KEYS.destLabel, d.label);
  if (d.source) {
    next.set(ASSISTANT_QUERY_KEYS.destSource, d.source);
  }
  if (d.placeIdentity?.provider && d.placeIdentity.providerPlaceId) {
    next.set(ASSISTANT_QUERY_KEYS.destProvider, d.placeIdentity.provider);
    next.set(ASSISTANT_QUERY_KEYS.destPlaceId, d.placeIdentity.providerPlaceId);
  }
  if (d.subtitle) {
    next.set(ASSISTANT_QUERY_KEYS.destSubtitle, d.subtitle);
  }
  if (state.candidateId) {
    next.set(ASSISTANT_QUERY_KEYS.candidate, state.candidateId);
  }

  return next;
}

export function stripAssistantUrlParams(searchParams: URLSearchParams): URLSearchParams {
  const next = new URLSearchParams();
  for (const [key, value] of unmanagedEntries(searchParams)) {
    next.append(key, value);
  }
  return next;
}

export function assistantDestinationIdentityKey(destination: Destination): string {
  if (destination.placeIdentity?.provider && destination.placeIdentity.providerPlaceId) {
    return `identity:${destination.placeIdentity.provider}:${destination.placeIdentity.providerPlaceId}`;
  }
  const factor = 1e5;
  const lat = (Math.round(destination.latitude * factor) / factor).toFixed(5);
  const lng = (Math.round(destination.longitude * factor) / factor).toFixed(5);
  return `coord:${lat}:${lng}`;
}
