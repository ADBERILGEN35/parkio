import type {
  ComposeDestinationSearchInput,
  ComposeDestinationSearchResult,
  Destination,
  DestinationSearchGroup,
  DestinationSearchItem,
  DestinationSearchSource,
  PlaceIdentity,
  SavedPlaceKind,
} from '@parkio/types';

const SOURCE_PRIORITY: Record<DestinationSearchSource, number> = {
  SAVED_PLACE: 0,
  FAVOURITE_DESTINATION: 1,
  RECENT_DESTINATION: 2,
  GEOCODING: 3,
};

const BLANK_ORDER: DestinationSearchGroup[] = [
  'SAVED_PLACE',
  'FAVOURITE_DESTINATION',
  'RECENT_DESTINATION',
];

const ACTIVE_ORDER: DestinationSearchGroup[] = [
  'SAVED_PLACE',
  'FAVOURITE_DESTINATION',
  'RECENT_DESTINATION',
  'GEOCODING',
];

const DEFAULT_GEOCODE_MIN = 3;
const DEFAULT_PER_SECTION = 8;
const COORD_SCALE = 5;

function roundCoord(value: number): string {
  const factor = 10 ** COORD_SCALE;
  return (Math.round(value * factor) / factor).toFixed(COORD_SCALE);
}

export function destinationDuplicateKey(
  latitude: number,
  longitude: number,
  placeIdentity?: PlaceIdentity | null,
): string {
  if (placeIdentity?.provider && placeIdentity?.providerPlaceId) {
    return `identity:${placeIdentity.provider}:${placeIdentity.providerPlaceId}`;
  }
  return `coord:${roundCoord(latitude)}:${roundCoord(longitude)}`;
}

/** Locale-aware Turkish-safe case fold for matching (deterministic). */
export function normalizeSearchText(value: string): string {
  return value.trim().replace(/\s+/g, ' ').toLocaleLowerCase('tr-TR');
}

function matchesQuery(query: string, title: string, subtitle?: string | null): boolean {
  if (!query) {
    return true;
  }
  const needle = normalizeSearchText(query);
  if (!needle) {
    return true;
  }
  const haystack = normalizeSearchText(`${title} ${subtitle ?? ''}`);
  return haystack.includes(needle);
}

function toDestination(input: {
  label: string;
  latitude: number;
  longitude: number;
  source: Destination['source'];
  placeIdentity?: Destination['placeIdentity'];
  subtitle?: string | null;
}): Destination {
  return {
    label: input.label,
    latitude: input.latitude,
    longitude: input.longitude,
    source: input.source,
    placeIdentity: input.placeIdentity ?? null,
    subtitle: input.subtitle ?? null,
  };
}

function savedTitle(kind: SavedPlaceKind, label: string): string {
  if (label.trim()) {
    return label.trim();
  }
  if (kind === 'HOME') {
    return 'Home';
  }
  if (kind === 'WORK') {
    return 'Work';
  }
  return label;
}

function savedSortRank(kind: SavedPlaceKind): number {
  if (kind === 'HOME') {
    return 0;
  }
  if (kind === 'WORK') {
    return 1;
  }
  return 2;
}

/**
 * Deterministic destination search composition (WP-SPA-07 Strategy A).
 *
 * - Blank / short query: HOME/WORK → CUSTOM → favourites → recents (no geocoding).
 * - Active query: matching saved → favourites → recents → geocoding.
 * - Cross-source dedupe by PlaceIdentity then 5-dp coordinates; highest-priority source wins.
 * - Label-only matches never merge.
 */
export function composeDestinationSearch(
  input: ComposeDestinationSearchInput,
): ComposeDestinationSearchResult {
  const query = input.query?.trim() ?? '';
  const geocodeMin = input.geocodeMinLength ?? DEFAULT_GEOCODE_MIN;
  const perSection = input.perSectionLimit ?? DEFAULT_PER_SECTION;
  const active = query.length >= geocodeMin;
  const order = active ? ACTIVE_ORDER : BLANK_ORDER;

  const favourites = input.favouriteDestinations ?? [];
  const recents = input.recentDestinations ?? [];
  const favouriteKeys = new Set(
    favourites.map((f) => destinationDuplicateKey(f.latitude, f.longitude, f.placeIdentity)),
  );
  const recentKeys = new Set(
    recents.map((r) => destinationDuplicateKey(r.latitude, r.longitude, r.placeIdentity)),
  );

  const candidates: DestinationSearchItem[] = [];

  const saved = [...(input.savedPlaces ?? [])].sort((a, b) => {
    const kindDelta = savedSortRank(a.kind) - savedSortRank(b.kind);
    if (kindDelta !== 0) {
      return kindDelta;
    }
    return a.id.localeCompare(b.id);
  });

  for (const place of saved) {
    const title = savedTitle(place.kind, place.label);
    if (active && !matchesQuery(query, title, place.subtitle)) {
      continue;
    }
    const destination = toDestination({
      label: title,
      latitude: place.latitude,
      longitude: place.longitude,
      source: place.source,
      placeIdentity: place.placeIdentity,
      subtitle: place.subtitle,
    });
    const key = destinationDuplicateKey(
      destination.latitude,
      destination.longitude,
      destination.placeIdentity,
    );
    candidates.push({
      id: `saved:${place.id}`,
      source: 'SAVED_PLACE',
      group: 'SAVED_PLACE',
      destination,
      title,
      subtitle: place.subtitle ?? null,
      savedPlaceKind: place.kind,
      alsoFavourite: favouriteKeys.has(key),
      alsoRecent: recentKeys.has(key),
    });
  }

  for (const fav of favourites) {
    if (active && !matchesQuery(query, fav.label, fav.subtitle)) {
      continue;
    }
    const destination = toDestination(fav);
    const key = destinationDuplicateKey(
      destination.latitude,
      destination.longitude,
      destination.placeIdentity,
    );
    candidates.push({
      id: `favourite:${fav.id}`,
      source: 'FAVOURITE_DESTINATION',
      group: 'FAVOURITE_DESTINATION',
      destination,
      title: fav.label,
      subtitle: fav.subtitle ?? null,
      alsoFavourite: true,
      alsoRecent: recentKeys.has(key),
    });
  }

  for (const recent of recents) {
    if (active && !matchesQuery(query, recent.label, recent.subtitle)) {
      continue;
    }
    const destination = toDestination(recent);
    const key = destinationDuplicateKey(
      destination.latitude,
      destination.longitude,
      destination.placeIdentity,
    );
    candidates.push({
      id: `recent:${recent.id}`,
      source: 'RECENT_DESTINATION',
      group: 'RECENT_DESTINATION',
      destination,
      title: recent.label,
      subtitle: recent.subtitle ?? null,
      alsoFavourite: favouriteKeys.has(key),
      alsoRecent: true,
    });
  }

  if (active) {
    for (const [index, geo] of (input.geocodingResults ?? []).entries()) {
      const destination = toDestination({
        label: geo.label,
        latitude: geo.latitude,
        longitude: geo.longitude,
        source: 'GEOCODING',
        placeIdentity: geo.placeIdentity,
        subtitle: geo.subtitle,
      });
      const key = destinationDuplicateKey(
        destination.latitude,
        destination.longitude,
        destination.placeIdentity,
      );
      candidates.push({
        id: `geocode:${index}:${key}`,
        source: 'GEOCODING',
        group: 'GEOCODING',
        destination,
        title: geo.label,
        subtitle: geo.subtitle ?? null,
        alsoFavourite: favouriteKeys.has(key),
        alsoRecent: recentKeys.has(key),
      });
    }
  }

  const winners = new Map<string, DestinationSearchItem>();
  for (const item of candidates) {
    const key = destinationDuplicateKey(
      item.destination.latitude,
      item.destination.longitude,
      item.destination.placeIdentity,
    );
    const existing = winners.get(key);
    if (!existing || SOURCE_PRIORITY[item.source] < SOURCE_PRIORITY[existing.source]) {
      const merged: DestinationSearchItem = {
        ...item,
        alsoFavourite: item.alsoFavourite || favouriteKeys.has(key),
        alsoRecent: item.alsoRecent || recentKeys.has(key),
      };
      winners.set(key, merged);
    } else {
      existing.alsoFavourite = existing.alsoFavourite || item.alsoFavourite || favouriteKeys.has(key);
      existing.alsoRecent = existing.alsoRecent || item.alsoRecent || recentKeys.has(key);
    }
  }

  const byGroup = new Map<DestinationSearchGroup, DestinationSearchItem[]>();
  for (const group of order) {
    byGroup.set(group, []);
  }
  for (const item of winners.values()) {
    const list = byGroup.get(item.group);
    if (list) {
      list.push(item);
    }
  }

  const sections = order
    .map((group) => ({
      group,
      items: (byGroup.get(group) ?? []).slice(0, perSection),
    }))
    .filter((section) => section.items.length > 0);

  return {
    sections,
    items: sections.flatMap((section) => section.items),
  };
}
