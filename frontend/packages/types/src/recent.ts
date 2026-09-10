/**
 * Recents contracts (WP-SPA-07).
 *
 * Recent destinations: confirmed trip targets only.
 * Recent parking: explicit parking-use recordings (municipal facilities in v1).
 */

import type { DestinationSource, PlaceIdentity } from './destination';

export type RecentParkingTargetKind = 'MUNICIPAL_FACILITY';

export interface RecentDestination {
  id: string;
  label: string;
  latitude: number;
  longitude: number;
  source: DestinationSource;
  placeIdentity?: PlaceIdentity | null;
  subtitle?: string | null;
  firstUsedAt: string;
  lastUsedAt: string;
  useCount: number;
}

export interface RecentDestinationListResponse {
  items: RecentDestination[];
}

/** Wire body for POST /api/v1/places/recents/destinations. */
export interface ConfirmRecentDestinationRequest {
  label: string;
  latitude: number;
  longitude: number;
  source?: DestinationSource | null;
  placeIdentity?: Pick<PlaceIdentity, 'provider' | 'providerPlaceId'> | null;
  subtitle?: string | null;
}

export interface RecentParking {
  id: string;
  targetKind: RecentParkingTargetKind;
  targetId: string;
  firstUsedAt: string;
  lastUsedAt: string;
  useCount: number;
}

export interface RecentParkingListResponse {
  items: RecentParking[];
}

export interface RecordRecentParkingRequest {
  targetKind?: RecentParkingTargetKind | null;
  targetId: string;
}
