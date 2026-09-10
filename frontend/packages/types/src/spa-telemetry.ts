/**
 * Smart Parking Experience product telemetry contracts (WP-SPA-12).
 *
 * Privacy-safe funnel events only — never coordinates, labels, user/session IDs,
 * facility/spot IDs, or raw search queries.
 */

import type { AssistantDestinationOrigin, QuickActionAvailability, QuickActionKind } from './quick-action';
import type { RankingStatus, RankingVersion } from './recommendation';
import type { DestinationSearchSource } from './destination-search';
import type { RecentParkingTargetKind } from './recent';

export type SpaTelemetryPlatform = 'web' | 'mobile_v2';

export type SpaTelemetryEventName =
  | 'assistant_opened'
  | 'destination_search_started'
  | 'destination_search_result_selected'
  | 'destination_confirmed'
  | 'recommendations_shown'
  | 'recommendations_partial'
  | 'recommendations_empty'
  | 'recommendations_failed'
  | 'recommendation_selected'
  | 'navigation_started'
  | 'quick_action_selected'
  | 'quick_action_unavailable'
  | 'parking_session_started'
  | 'park_here_failed'
  | 'return_to_car_started'
  | 'parking_session_ended'
  | 'ranking_fallback'
  | 'recent_parking_record_failed'
  | 'time_to_confident_choice';

/** Coarse count buckets — no raw counts required in payloads. */
export type SpaCountBucket =
  | '0'
  | '1'
  | '2_3'
  | '4_8'
  | '9_plus';

export type SpaLatencyBucket =
  | 'lt_5s'
  | '5_15s'
  | '15_30s'
  | '30_60s'
  | 'gt_60s';

export type SpaParkHereFailureReason = 'conflict' | 'offline' | 'error';

export type SpaParkHereOriginSurface =
  | 'municipal_preview'
  | 'municipal_detail'
  | 'recommendation'
  | 'map_location'
  | 'unknown';

export type SpaSessionEndOutcome = 'completed' | 'cancelled';

/**
 * Allowed product-event dimensions. Extra keys are rejected by the sanitizer.
 * Do not add forbidden identity fields here.
 */
export interface SpaTelemetryParams {
  platform?: SpaTelemetryPlatform;
  appVersion?: string;
  assistantOrigin?: AssistantDestinationOrigin;
  searchSource?: DestinationSearchSource;
  candidateChannel?: 'MUNICIPAL_FACILITY' | 'COMMUNITY_SPOT';
  /** 0-based position among shown candidates; coarse and non-identifying. */
  recommendationPosition?: number;
  candidateCountBucket?: SpaCountBucket;
  partial?: boolean;
  rankingVersion?: RankingVersion | string;
  rankingStatus?: RankingStatus | string;
  quickActionKind?: QuickActionKind;
  quickActionAvailability?: QuickActionAvailability;
  targetKind?: RecentParkingTargetKind;
  originSurface?: SpaParkHereOriginSurface;
  failureReason?: SpaParkHereFailureReason;
  sessionOutcome?: SpaSessionEndOutcome;
  timeToChoiceBucket?: SpaLatencyBucket;
  /** Short-lived anonymous journey correlation — never a user/device id. */
  journeyId?: string;
}

export const SPA_TELEMETRY_EVENT_NAMES = [
  'assistant_opened',
  'destination_search_started',
  'destination_search_result_selected',
  'destination_confirmed',
  'recommendations_shown',
  'recommendations_partial',
  'recommendations_empty',
  'recommendations_failed',
  'recommendation_selected',
  'navigation_started',
  'quick_action_selected',
  'quick_action_unavailable',
  'parking_session_started',
  'park_here_failed',
  'return_to_car_started',
  'parking_session_ended',
  'ranking_fallback',
  'recent_parking_record_failed',
  'time_to_confident_choice',
] as const satisfies readonly SpaTelemetryEventName[];
