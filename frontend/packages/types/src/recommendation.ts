/**
 * Destination-scoped parking recommendations (WP-SPA-05).
 *
 * Composes municipal facilities and community spots around a Destination.
 * Baseline order is distance-ascending — not weighted ranking (WP-SPA-06).
 */

import type { Destination, DestinationSource, PlaceIdentity } from './destination';
import type { MunicipalOccupancyFreshness } from './municipal';

export type ParkingCandidateChannel = 'COMMUNITY_SPOT' | 'MUNICIPAL_FACILITY';

export type InventoryChannelStatus = 'AVAILABLE' | 'EMPTY' | 'DEGRADED' | 'DISABLED';

export type RecommendationReasonCode =
  | 'CLOSE_TO_DESTINATION'
  | 'LIVE_AVAILABILITY'
  | 'HIGH_CAPACITY'
  | 'STATIC_INVENTORY'
  | 'COMMUNITY_FRESH'
  | 'INVENTORY_DEGRADED';

export type CandidateAvailabilityKind = 'MUNICIPAL' | 'COMMUNITY';

export interface RecommendationReason {
  code: RecommendationReasonCode;
  parameters?: Record<string, unknown> | null;
  messageKey?: string | null;
}

export interface CandidateAvailability {
  kind: CandidateAvailabilityKind;
  freshness?: MunicipalOccupancyFreshness | null;
  availableSpaces?: number | null;
  occupiedSpaces?: number | null;
  capacityTotal?: number | null;
  sourceLabel?: string | null;
  observationTimestamp?: string | null;
  communityStatus?: string | null;
  expiresAt?: string | null;
}

export interface ParkingCandidate {
  id: string;
  channel: ParkingCandidateChannel;
  refId: string;
  title: string;
  latitude: number;
  longitude: number;
  distanceMeters: number;
  availability?: CandidateAvailability | null;
  sourceLabel?: string | null;
  baselineOrder: number;
  reasons: RecommendationReason[];
}

export interface InventoryStatus {
  community: InventoryChannelStatus;
  municipal: InventoryChannelStatus;
}

/** Destination input on recommend requests — canonicalKey optional on write. */
export interface RecommendationDestinationInput {
  label: string;
  latitude: number;
  longitude: number;
  source?: DestinationSource | null;
  placeIdentity?: Pick<PlaceIdentity, 'provider' | 'providerPlaceId'> | null;
  subtitle?: string | null;
}

export interface RecommendationRequest {
  destination: RecommendationDestinationInput;
  radiusMeters?: number | null;
  limit?: number | null;
  includeCommunity?: boolean | null;
  includeMunicipal?: boolean | null;
}

export interface RecommendationResponse {
  destination: Destination;
  generatedAt: string;
  partial: boolean;
  inventoryStatus: InventoryStatus;
  candidates: ParkingCandidate[];
  warnings?: RecommendationReason[] | null;
}
