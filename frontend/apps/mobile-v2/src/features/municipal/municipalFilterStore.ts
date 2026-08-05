import { create } from 'zustand';
import { readJson, writeJson } from '@/services/jsonStore';
import {
  DEFAULT_MUNICIPAL_MAP_FILTERS,
  resetMunicipalMapFilters,
  sanitizeMunicipalMapFilters,
  type MunicipalMapFilters,
  type MunicipalOccupancyFilter,
  type MunicipalRadiusMeters,
  type MunicipalSourceFilter,
} from './municipalFilterModel';

const STORE_KEY = 'municipal-map-filters';

interface MunicipalFilterState extends MunicipalMapFilters {
  hydrated: boolean;
  hydrate: () => Promise<void>;
  setLayerEnabled: (enabled: boolean) => void;
  setSource: (source: MunicipalSourceFilter) => void;
  setOccupancy: (occupancy: MunicipalOccupancyFilter) => void;
  setRadiusMeters: (radiusMeters: MunicipalRadiusMeters) => void;
  /** Restore source / occupancy / radius defaults; keep layer visibility. */
  resetFilters: () => void;
}

function snapshot(state: MunicipalFilterState): MunicipalMapFilters {
  return {
    version: state.version,
    layerEnabled: state.layerEnabled,
    source: state.source,
    occupancy: state.occupancy,
    radiusMeters: state.radiusMeters,
  };
}

function persist(state: MunicipalFilterState): void {
  void writeJson(STORE_KEY, snapshot(state));
}

export const useMunicipalFilterStore = create<MunicipalFilterState>((set, get) => ({
  ...DEFAULT_MUNICIPAL_MAP_FILTERS,
  hydrated: false,

  hydrate: async () => {
    const stored = await readJson<unknown>(STORE_KEY);
    const sanitized = sanitizeMunicipalMapFilters(stored);
    set({ ...sanitized, hydrated: true });
  },

  setLayerEnabled: (layerEnabled) => {
    set({ layerEnabled });
    persist(get());
  },

  setSource: (source) => {
    set({ source });
    persist(get());
  },

  setOccupancy: (occupancy) => {
    set({ occupancy });
    persist(get());
  },

  setRadiusMeters: (radiusMeters) => {
    set({ radiusMeters });
    persist(get());
  },

  resetFilters: () => {
    const next = resetMunicipalMapFilters(snapshot(get()));
    set(next);
    persist(get());
  },
}));

/** Selectors for map wiring — keep filter snapshot without store methods. */
export function selectMunicipalMapFilters(state: MunicipalFilterState): MunicipalMapFilters {
  return snapshot(state);
}
