import type { MunicipalFacilityType } from '@parkio/types';
import {
  EMPTY_MUNICIPAL_FILTERS,
  type MunicipalFacilityFilters,
} from '@/lib/spotDiscovery';

export type MapDiscoveryUrlState = {
  communityLayerVisible: boolean;
  municipalLayerVisible: boolean;
  municipalFilters: MunicipalFacilityFilters;
};

export const MAP_DISCOVERY_QUERY_KEYS = {
  communityLayerVisible: 'communityLayer',
  municipalLayerVisible: 'municipalLayer',
  municipalAvailability: 'municipalAvailability',
  municipalSources: 'municipalSources',
  municipalTypes: 'municipalTypes',
  municipalProvenance: 'municipalProvenance',
} as const;

const MANAGED_KEYS = new Set<string>(Object.values(MAP_DISCOVERY_QUERY_KEYS));

const MUNICIPAL_FACILITY_TYPES: readonly MunicipalFacilityType[] = [
  'ON_STREET',
  'OFF_STREET',
  'UNKNOWN',
];

export const DEFAULT_MAP_DISCOVERY_URL_STATE: MapDiscoveryUrlState = {
  communityLayerVisible: true,
  municipalLayerVisible: true,
  municipalFilters: EMPTY_MUNICIPAL_FILTERS,
};

export type MapDiscoveryUrlStateOptions = {
  municipalDiscoveryEnabled?: boolean;
  availableSourceLabels?: readonly string[];
  availableFacilityTypes?: readonly MunicipalFacilityType[];
};

function lastMeaningfulValue(values: readonly string[]): string | null {
  for (let index = values.length - 1; index >= 0; index -= 1) {
    const normalized = values[index]?.trim() ?? '';
    if (normalized.length > 0) return normalized;
  }
  return null;
}

function parseCsvValues(values: readonly string[]): string[] {
  return values
    .flatMap((value) => value.split(','))
    .map((value) => value.trim())
    .filter((value) => value.length > 0);
}

function dedupeSorted(values: readonly string[]): string[] {
  return [...new Set(values)].sort((left, right) => left.localeCompare(right));
}

function parseLayerVisible(values: readonly string[]): boolean {
  const value = lastMeaningfulValue(values);
  if (value === '0') return false;
  if (value === '1') return true;
  return true;
}

function normalizeMunicipalFilters(
  filters: MunicipalFacilityFilters,
  {
    availableSourceLabels,
    availableFacilityTypes,
  }: Pick<MapDiscoveryUrlStateOptions, 'availableSourceLabels' | 'availableFacilityTypes'> = {},
): MunicipalFacilityFilters {
  const availability =
    filters.availability === 'available' ||
    filters.availability === 'unavailable' ||
    filters.availability === 'unknown'
      ? filters.availability
      : 'all';

  const sourceLabels = dedupeSorted(filters.sourceLabels).filter(
    (label) => !availableSourceLabels || availableSourceLabels.includes(label),
  );

  const facilityTypes = dedupeSorted(filters.facilityTypes).filter(
    (type): type is MunicipalFacilityType =>
      MUNICIPAL_FACILITY_TYPES.includes(type as MunicipalFacilityType) &&
      (!availableFacilityTypes || availableFacilityTypes.includes(type as MunicipalFacilityType)),
  ) as MunicipalFacilityType[];

  return {
    availability,
    sourceLabels,
    facilityTypes,
    provenanceOnly: Boolean(filters.provenanceOnly),
  };
}

function managedEntries(searchParams: URLSearchParams): Array<[string, string]> {
  return Array.from(searchParams.entries()).filter(([key]) => !MANAGED_KEYS.has(key));
}

export function parseMapDiscoveryUrlState(
  searchParams: URLSearchParams,
  {
    municipalDiscoveryEnabled = true,
    availableSourceLabels,
    availableFacilityTypes,
  }: MapDiscoveryUrlStateOptions = {},
): MapDiscoveryUrlState {
  if (!municipalDiscoveryEnabled) {
    return DEFAULT_MAP_DISCOVERY_URL_STATE;
  }

  const availabilityValue = lastMeaningfulValue(
    searchParams.getAll(MAP_DISCOVERY_QUERY_KEYS.municipalAvailability),
  );
  const availability =
    availabilityValue === 'available' ||
    availabilityValue === 'unavailable' ||
    availabilityValue === 'unknown'
      ? availabilityValue
      : 'all';

  return {
    communityLayerVisible: parseLayerVisible(
      searchParams.getAll(MAP_DISCOVERY_QUERY_KEYS.communityLayerVisible),
    ),
    municipalLayerVisible: parseLayerVisible(
      searchParams.getAll(MAP_DISCOVERY_QUERY_KEYS.municipalLayerVisible),
    ),
    municipalFilters: normalizeMunicipalFilters(
      {
        availability,
        sourceLabels: parseCsvValues(searchParams.getAll(MAP_DISCOVERY_QUERY_KEYS.municipalSources)),
        facilityTypes: parseCsvValues(
          searchParams.getAll(MAP_DISCOVERY_QUERY_KEYS.municipalTypes),
        ) as MunicipalFacilityType[],
        provenanceOnly:
          lastMeaningfulValue(searchParams.getAll(MAP_DISCOVERY_QUERY_KEYS.municipalProvenance)) ===
          '1',
      },
      { availableSourceLabels, availableFacilityTypes },
    ),
  };
}

export function serializeMapDiscoveryUrlState(
  searchParams: URLSearchParams,
  state: MapDiscoveryUrlState,
  {
    municipalDiscoveryEnabled = true,
    availableSourceLabels,
    availableFacilityTypes,
  }: MapDiscoveryUrlStateOptions = {},
): URLSearchParams {
  const next = new URLSearchParams();
  for (const [key, value] of managedEntries(searchParams)) {
    next.append(key, value);
  }

  if (!municipalDiscoveryEnabled) {
    return next;
  }

  const municipalFilters = normalizeMunicipalFilters(state.municipalFilters, {
    availableSourceLabels,
    availableFacilityTypes,
  });

  if (!state.communityLayerVisible) {
    next.append(MAP_DISCOVERY_QUERY_KEYS.communityLayerVisible, '0');
  }
  if (!state.municipalLayerVisible) {
    next.append(MAP_DISCOVERY_QUERY_KEYS.municipalLayerVisible, '0');
  }
  if (municipalFilters.availability !== 'all') {
    next.append(MAP_DISCOVERY_QUERY_KEYS.municipalAvailability, municipalFilters.availability);
  }
  if (municipalFilters.sourceLabels.length > 0) {
    next.append(
      MAP_DISCOVERY_QUERY_KEYS.municipalSources,
      dedupeSorted(municipalFilters.sourceLabels).join(','),
    );
  }
  if (municipalFilters.facilityTypes.length > 0) {
    next.append(
      MAP_DISCOVERY_QUERY_KEYS.municipalTypes,
      dedupeSorted(municipalFilters.facilityTypes).join(','),
    );
  }
  if (municipalFilters.provenanceOnly) {
    next.append(MAP_DISCOVERY_QUERY_KEYS.municipalProvenance, '1');
  }

  return next;
}

export function canonicalizeMapDiscoveryUrlState(
  searchParams: URLSearchParams,
  options: MapDiscoveryUrlStateOptions = {},
): URLSearchParams {
  const parsed = parseMapDiscoveryUrlState(searchParams, options);
  return serializeMapDiscoveryUrlState(searchParams, parsed, options);
}

export function mapDiscoveryUrlStateKey(
  state: MapDiscoveryUrlState,
  options: MapDiscoveryUrlStateOptions = {},
): string {
  return serializeMapDiscoveryUrlState(new URLSearchParams(), state, options).toString();
}
