import { create } from 'zustand';
import type { Destination, DestinationSource } from '@parkio/types';
import { readJson, writeJson } from '@/services/jsonStore';

const STORE_KEY = 'spa-assistant-destination';
const SCHEMA_VERSION = 1 as const;

type PersistedPayload = {
  version: typeof SCHEMA_VERSION;
  destination: Destination | null;
};

interface AssistantPersistState {
  hydrated: boolean;
  destination: Destination | null;
  hydrate: () => Promise<void>;
  setDestination: (destination: Destination | null) => void;
  clearDestination: () => void;
}

const SOURCES: readonly DestinationSource[] = ['GEOCODING', 'MAP_PIN', 'SYSTEM'];

/** Sanitize persisted destination — invalid coords/label → null. */
export function sanitizePersistedDestination(raw: unknown): Destination | null {
  if (!raw || typeof raw !== 'object') return null;
  const payload = raw as PersistedPayload;
  if (payload.version !== SCHEMA_VERSION) return null;
  const d = payload.destination;
  if (!d || typeof d !== 'object') return null;
  if (typeof d.label !== 'string' || d.label.trim().length === 0) return null;
  if (typeof d.latitude !== 'number' || typeof d.longitude !== 'number') return null;
  if (!Number.isFinite(d.latitude) || !Number.isFinite(d.longitude)) return null;
  if (d.latitude < -90 || d.latitude > 90 || d.longitude < -180 || d.longitude > 180) {
    return null;
  }
  const source: DestinationSource =
    d.source && SOURCES.includes(d.source as DestinationSource)
      ? (d.source as DestinationSource)
      : 'GEOCODING';
  let placeIdentity: Destination['placeIdentity'] = null;
  if (
    d.placeIdentity &&
    typeof d.placeIdentity.provider === 'string' &&
    typeof d.placeIdentity.providerPlaceId === 'string'
  ) {
    const provider = d.placeIdentity.provider;
    const providerPlaceId = d.placeIdentity.providerPlaceId;
    placeIdentity = {
      provider,
      providerPlaceId,
      canonicalKey:
        typeof d.placeIdentity.canonicalKey === 'string'
          ? d.placeIdentity.canonicalKey
          : `${provider}:${providerPlaceId}`,
    };
  }
  return {
    label: d.label.trim(),
    latitude: d.latitude,
    longitude: d.longitude,
    source,
    placeIdentity,
    subtitle: typeof d.subtitle === 'string' ? d.subtitle : null,
  };
}

function persist(destination: Destination | null): void {
  const payload: PersistedPayload = { version: SCHEMA_VERSION, destination };
  void writeJson(STORE_KEY, payload);
}

/**
 * Confirmed destination only — survives tab navigation / process restart.
 * Candidate selection and search UI stay in React state (not persisted).
 * Does not duplicate server recents.
 */
export const useAssistantDestinationStore = create<AssistantPersistState>((set) => ({
  hydrated: false,
  destination: null,

  hydrate: async () => {
    const stored = await readJson<unknown>(STORE_KEY);
    const destination = sanitizePersistedDestination(stored);
    set({ destination, hydrated: true });
  },

  setDestination: (destination) => {
    set({ destination });
    persist(destination);
  },

  clearDestination: () => {
    set({ destination: null });
    persist(null);
  },
}));
