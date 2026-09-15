/**
 * Destination-scoped parking recommendations (WP-SPA-05 / WP-SPA-06).
 *
 * Composes municipal facilities and community spots around a Destination.
 * When ranking is disabled, order is distance-ascending (SPA-05 baseline).
 * When ranking is enabled, order is deterministic weighted ranking (SPA-06).
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
  | 'INVENTORY_DEGRADED'
  | 'FAVOURITE'
  | 'HIGH_CONFIDENCE';

export type RankingVersion = 'DISTANCE_BASELINE_V1' | 'DETERMINISTIC_V1';

export type RankingStatus = 'DISABLED' | 'APPLIED' | 'FALLBACK';

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

export interface CandidateScoreBreakdown {
  distance: number;
  freshness: number;
  capacity: number;
  confidence: number;
  favourite: number;
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
  /** Present when deterministic ranking was applied. */
  score?: number | null;
  scoreBreakdown?: CandidateScoreBreakdown | null;
  rankingVersion?: RankingVersion | string | null;
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

export type RankingEvaluationOutcomeType =
  | 'RECOMMENDATION_SELECTED'
  | 'NAVIGATION_STARTED'
  | 'PARKING_SESSION_STARTED'
  | 'RETURN_TO_CAR_STARTED'
  | 'PARKING_SESSION_ENDED';

export type RankingEvaluationPlatform = 'WEB' | 'MOBILE_V2' | 'UNKNOWN';

export type RankingEvaluationOutcomeStatus =
  | 'RECORDED'
  | 'DUPLICATE'
  | 'DISABLED'
  | 'PERSISTENCE_FAILED';

/** Privacy-safe ranking evaluation outcome submission (WP-SPA-14B). */
export interface RankingEvaluationOutcomeRequest {
  evaluationId: string;
  candidateOrdinal: number;
  outcomeType: RankingEvaluationOutcomeType;
  platform?: RankingEvaluationPlatform | null;
  latencyBucket?: string | null;
}

export interface RankingEvaluationOutcomeResponse {
  status: RankingEvaluationOutcomeStatus | string;
}

export interface RecommendationResponse {
  destination: Destination;
  generatedAt: string;
  partial: boolean;
  inventoryStatus: InventoryStatus;
  candidates: ParkingCandidate[];
  warnings?: RecommendationReason[] | null;
  rankingVersion?: RankingVersion | null;
  rankingStatus?: RankingStatus | null;
  /** Opaque privacy-safe ranking evaluation correlation token (WP-SPA-14B). */
  evaluationId?: string | null;
}
