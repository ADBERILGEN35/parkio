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
  | 'time_to_confident_choice'
  // Y04 product analytics foundation (client.analytics.v1)
  | 'analytics_session_started'
  | 'analytics_session_ended'
  | 'screen_viewed'
  | 'screen_engagement_summary'
  | 'map_ready'
  | 'map_init_failed'
  | 'filter_applied'
  | 'facility_preview_opened'
  | 'facility_detail_opened'
  | 'returned_to_map'
  | 'retry_attempted'
  | 'retry_outcome'
  | 'auth_login_attempted'
  | 'auth_login_api_succeeded'
  | 'auth_login_failed'
  | 'auth_signup_attempted'
  | 'auth_signup_api_succeeded'
  | 'auth_signup_failed';

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

/** Normalized route / screen id — never raw URL or query string. */
export type AnalyticsScreenName =
  | 'map'
  | 'facility_detail'
  | 'public_explore'
  | 'login'
  | 'register'
  | 'profile'
  | 'preferences'
  | 'other';

export type AnalyticsSelectionOrigin = 'map' | 'list' | 'deeplink' | 'unknown';

export type AnalyticsFilterKind =
  | 'availability'
  | 'source'
  | 'type'
  | 'radius'
  | 'reset'
  | 'community'
  | 'other';

export type AnalyticsMapErrorCode =
  | 'load_failed'
  | 'style_failed'
  | 'bridge_error'
  | 'timeout'
  | 'unknown';

export type AnalyticsAuthFailureReason =
  | 'invalid_credentials'
  | 'not_verified'
  | 'network'
  | 'validation'
  | 'persistence'
  | 'unknown';

export type AnalyticsRetryOutcome = 'succeeded' | 'failed' | 'abandoned';

export type AnalyticsSessionEndReason =
  | 'logout'
  | 'opt_out'
  | 'idle'
  | 'background'
  | 'navigate_away'
  | 'incomplete'
  | 'app_exit';

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
  // Y04 fields
  schemaVersion?: number;
  screenName?: AnalyticsScreenName;
  previousScreenName?: AnalyticsScreenName;
  selectionOrigin?: AnalyticsSelectionOrigin;
  filterKind?: AnalyticsFilterKind;
  mapErrorCode?: AnalyticsMapErrorCode;
  authFailureReason?: AnalyticsAuthFailureReason;
  retryOutcome?: AnalyticsRetryOutcome;
  sessionEndReason?: AnalyticsSessionEndReason;
  /** Active milliseconds on screen (monotonic, idle-gated). */
  activeDurationMs?: number;
  /** True when duration estimate is incomplete (crash / no exit). */
  incomplete?: boolean;
  /** True when event was queued offline and flushed later. */
  deferred?: boolean;
  checkpointSeq?: number;
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
  'analytics_session_started',
  'analytics_session_ended',
  'screen_viewed',
  'screen_engagement_summary',
  'map_ready',
  'map_init_failed',
  'filter_applied',
  'facility_preview_opened',
  'facility_detail_opened',
  'returned_to_map',
  'retry_attempted',
  'retry_outcome',
  'auth_login_attempted',
  'auth_login_api_succeeded',
  'auth_login_failed',
  'auth_signup_attempted',
  'auth_signup_api_succeeded',
  'auth_signup_failed',
] as const satisfies readonly SpaTelemetryEventName[];

/** client.analytics.v1 contract version for Y04 payloads. */
export const CLIENT_ANALYTICS_SCHEMA_VERSION = 1;
