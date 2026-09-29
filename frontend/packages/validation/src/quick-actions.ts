import type {
  AssistantDestinationOrigin,
  Destination,
  DestinationSearchItem,
  DestinationSearchSource,
  FavouriteDestination,
  QuickActionAvailability,
  QuickActionDescriptor,
  QuickActionKind,
  RecentDestination,
  SavedPlace,
  SavedPlaceKind,
} from '@parkio/types';

const DESTINATION_ORIGINS: readonly AssistantDestinationOrigin[] = [
  'SEARCH',
  'HOME_QUICK_ACTION',
  'WORK_QUICK_ACTION',
  'FAVOURITE_DESTINATION_QUICK_ACTION',
  'RECENT_DESTINATION_QUICK_ACTION',
] as const;

/** Product order: Home → Work → Parked car (when active) → favourites → recents. */
export const QUICK_ACTION_BASE_ORDER: readonly QuickActionKind[] = [
  'HOME',
  'WORK',
  'PARKED_CAR',
  'FAVOURITE_DESTINATIONS',
  'FAVOURITE_PARKING',
  'RECENT_DESTINATIONS',
] as const;

export function isAssistantDestinationOrigin(
  value: unknown,
): value is AssistantDestinationOrigin {
  return (
    typeof value === 'string' &&
    (DESTINATION_ORIGINS as readonly string[]).includes(value)
  );
}

export function destinationFromSavedPlace(place: SavedPlace): Destination {
  return {
    label: place.label.trim() || defaultSavedLabel(place.kind),
    latitude: place.latitude,
    longitude: place.longitude,
    source: place.source,
    placeIdentity: place.placeIdentity ?? null,
    subtitle: place.subtitle ?? null,
  };
}

export function destinationFromFavouriteDestination(
  fav: FavouriteDestination,
): Destination {
  return {
    label: fav.label.trim(),
    latitude: fav.latitude,
    longitude: fav.longitude,
    source: fav.source,
    placeIdentity: fav.placeIdentity ?? null,
    subtitle: fav.subtitle ?? null,
  };
}

export function destinationFromRecentDestination(recent: RecentDestination): Destination {
  return {
    label: recent.label.trim(),
    latitude: recent.latitude,
    longitude: recent.longitude,
    source: recent.source,
    placeIdentity: recent.placeIdentity ?? null,
    subtitle: recent.subtitle ?? null,
  };
}

function defaultSavedLabel(kind: SavedPlaceKind): string {
  if (kind === 'HOME') return 'Home';
  if (kind === 'WORK') return 'Work';
  return 'Saved';
}

/** Wrap a Destination for the existing confirmDestination(search item) path. */
export function asAssistantSearchItem(
  destination: Destination,
  source: DestinationSearchSource,
  extras?: {
    id?: string;
    savedPlaceKind?: SavedPlaceKind | null;
  },
): DestinationSearchItem {
  return {
    id: extras?.id ?? `qa:${source}:${destination.label}:${destination.latitude}`,
    source,
    group: source,
    destination,
    title: destination.label,
    subtitle: destination.subtitle ?? null,
    savedPlaceKind: extras?.savedPlaceKind ?? null,
  };
}

export type QuickActionSourceSnapshot = {
  savedPlaces?: SavedPlace[] | null;
  savedPlacesStatus: 'idle' | 'pending' | 'success' | 'error';
  favouriteDestinations?: FavouriteDestination[] | null;
  favouriteDestinationsStatus: 'idle' | 'pending' | 'success' | 'error';
  favouriteParkingCount?: number | null;
  favouriteParkingStatus: 'idle' | 'pending' | 'success' | 'error';
  recentDestinations?: RecentDestination[] | null;
  recentDestinationsStatus: 'idle' | 'pending' | 'success' | 'error';
  /** True when an ACTIVE session with usable return coordinates exists. */
  parkedCarAvailable: boolean;
  parkedCarStatus: 'idle' | 'pending' | 'success' | 'error' | 'disabled';
};

function findSaved(places: SavedPlace[] | null | undefined, kind: SavedPlaceKind) {
  return places?.find((p) => p.kind === kind) ?? null;
}

export function homeAvailability(snapshot: QuickActionSourceSnapshot): QuickActionAvailability {
  if (snapshot.savedPlacesStatus === 'pending' || snapshot.savedPlacesStatus === 'idle') {
    return 'LOADING';
  }
  if (snapshot.savedPlacesStatus === 'error') return 'ERROR';
  return findSaved(snapshot.savedPlaces, 'HOME') ? 'AVAILABLE' : 'UNCONFIGURED';
}

export function workAvailability(snapshot: QuickActionSourceSnapshot): QuickActionAvailability {
  if (snapshot.savedPlacesStatus === 'pending' || snapshot.savedPlacesStatus === 'idle') {
    return 'LOADING';
  }
  if (snapshot.savedPlacesStatus === 'error') return 'ERROR';
  return findSaved(snapshot.savedPlaces, 'WORK') ? 'AVAILABLE' : 'UNCONFIGURED';
}

export function favouriteDestinationsAvailability(
  snapshot: QuickActionSourceSnapshot,
): QuickActionAvailability {
  if (
    snapshot.favouriteDestinationsStatus === 'pending' ||
    snapshot.favouriteDestinationsStatus === 'idle'
  ) {
    return 'LOADING';
  }
  if (snapshot.favouriteDestinationsStatus === 'error') return 'ERROR';
  return (snapshot.favouriteDestinations?.length ?? 0) > 0 ? 'AVAILABLE' : 'EMPTY';
}

export function favouriteParkingAvailability(
  snapshot: QuickActionSourceSnapshot,
): QuickActionAvailability {
  if (snapshot.favouriteParkingStatus === 'pending' || snapshot.favouriteParkingStatus === 'idle') {
    return 'LOADING';
  }
  if (snapshot.favouriteParkingStatus === 'error') return 'ERROR';
  return (snapshot.favouriteParkingCount ?? 0) > 0 ? 'AVAILABLE' : 'EMPTY';
}

export function recentDestinationsAvailability(
  snapshot: QuickActionSourceSnapshot,
): QuickActionAvailability {
  if (
    snapshot.recentDestinationsStatus === 'pending' ||
    snapshot.recentDestinationsStatus === 'idle'
  ) {
    return 'LOADING';
  }
  if (snapshot.recentDestinationsStatus === 'error') return 'ERROR';
  return (snapshot.recentDestinations?.length ?? 0) > 0 ? 'AVAILABLE' : 'EMPTY';
}

export function parkedCarAvailability(
  snapshot: QuickActionSourceSnapshot,
): QuickActionAvailability {
  if (snapshot.parkedCarStatus === 'disabled') return 'UNAVAILABLE';
  if (snapshot.parkedCarStatus === 'pending' || snapshot.parkedCarStatus === 'idle') {
    return 'LOADING';
  }
  if (snapshot.parkedCarStatus === 'error') return 'ERROR';
  return snapshot.parkedCarAvailable ? 'AVAILABLE' : 'UNAVAILABLE';
}

/** Build ordered descriptors; PARKED_CAR omitted when UNAVAILABLE to save chrome. */
export function buildQuickActionDescriptors(
  snapshot: QuickActionSourceSnapshot,
): QuickActionDescriptor[] {
  const descriptors: QuickActionDescriptor[] = [];

  for (const kind of QUICK_ACTION_BASE_ORDER) {
    let availability: QuickActionAvailability;
    let count: number | undefined;

    switch (kind) {
      case 'HOME':
        availability = homeAvailability(snapshot);
        break;
      case 'WORK':
        availability = workAvailability(snapshot);
        break;
      case 'PARKED_CAR':
        availability = parkedCarAvailability(snapshot);
        if (availability === 'UNAVAILABLE' || availability === 'LOADING') {
          // Hide until we know an active session (or error — show disabled chip).
          if (availability === 'UNAVAILABLE') continue;
          if (availability === 'LOADING') continue;
        }
        break;
      case 'FAVOURITE_DESTINATIONS':
        availability = favouriteDestinationsAvailability(snapshot);
        count = snapshot.favouriteDestinations?.length;
        break;
      case 'FAVOURITE_PARKING':
        availability = favouriteParkingAvailability(snapshot);
        count = snapshot.favouriteParkingCount ?? undefined;
        break;
      case 'RECENT_DESTINATIONS':
        availability = recentDestinationsAvailability(snapshot);
        count = snapshot.recentDestinations?.length;
        break;
      default:
        continue;
    }

    descriptors.push({ kind, availability, count });
  }

  return descriptors;
}

export function resolveHomePlace(places: SavedPlace[] | null | undefined): SavedPlace | null {
  return findSaved(places, 'HOME');
}

export function resolveWorkPlace(places: SavedPlace[] | null | undefined): SavedPlace | null {
  return findSaved(places, 'WORK');
}
