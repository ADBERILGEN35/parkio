/**
 * Quick Actions presentation model (WP-SPA-10).
 *
 * Client-only shortcuts into existing SavedPlace / Favourites / Recents /
 * ParkingSession flows. Not a backend persistence contract.
 */

/** Destination-producing selection origins (telemetry / debugging). */
export type AssistantDestinationOrigin =
  | 'SEARCH'
  | 'HOME_QUICK_ACTION'
  | 'WORK_QUICK_ACTION'
  | 'FAVOURITE_DESTINATION_QUICK_ACTION'
  | 'RECENT_DESTINATION_QUICK_ACTION';

export type QuickActionKind =
  | 'HOME'
  | 'WORK'
  | 'FAVOURITE_DESTINATIONS'
  | 'FAVOURITE_PARKING'
  | 'RECENT_DESTINATIONS'
  | 'PARKED_CAR';

/**
 * Deterministic availability for a shortcut.
 * Absence is UNCONFIGURED / EMPTY / UNAVAILABLE — never ERROR.
 */
export type QuickActionAvailability =
  | 'AVAILABLE'
  | 'UNCONFIGURED'
  | 'EMPTY'
  | 'UNAVAILABLE'
  | 'ERROR'
  | 'LOADING';

export type QuickActionIconIntent =
  | 'home'
  | 'work'
  | 'favourite_destination'
  | 'favourite_parking'
  | 'recent'
  | 'parked_car';

export interface QuickActionDescriptor {
  kind: QuickActionKind;
  availability: QuickActionAvailability;
  /** Optional count badge (favourites / recents). */
  count?: number;
}
