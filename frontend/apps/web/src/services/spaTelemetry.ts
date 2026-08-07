/**
 * SPA funnel telemetry helpers (WP-SPA-12) for web.
 * In-memory journey correlation + time-to-confident-choice only.
 */

import type {
  AssistantDestinationOrigin,
  DestinationSearchSource,
  ParkingCandidate,
  QuickActionAvailability,
  QuickActionKind,
  RecentParkingTargetKind,
  RecommendationResponse,
  SpaParkHereFailureReason,
  SpaParkHereOriginSurface,
  SpaSessionEndOutcome,
  SpaTelemetryEventName,
  SpaTelemetryParams,
} from '@parkio/types';
import {
  bucketCandidateCount,
  bucketLatencyMs,
  createSpaJourneyId,
} from '@parkio/validation';
import { trackProductEvent } from './productAnalytics';

const PLATFORM = 'web' as const;

let journeyId: string | null = null;
let destinationConfirmedAtMs: number | null = null;
let confidentChoiceRecorded = false;
const emittedRecommendationKeys = new Set<string>();

export function resetSpaTelemetryForTests(): void {
  journeyId = null;
  destinationConfirmedAtMs = null;
  confidentChoiceRecorded = false;
  emittedRecommendationKeys.clear();
}

function ensureJourney(): string {
  if (!journeyId) journeyId = createSpaJourneyId();
  return journeyId;
}

function track(name: SpaTelemetryEventName, params?: SpaTelemetryParams): void {
  trackProductEvent(name, {
    platform: PLATFORM,
    journeyId: ensureJourney(),
    ...params,
  });
}

function maybeEmitTimeToConfidentChoice(): void {
  if (destinationConfirmedAtMs == null || confidentChoiceRecorded) return;
  confidentChoiceRecorded = true;
  track('time_to_confident_choice', {
    timeToChoiceBucket: bucketLatencyMs(Date.now() - destinationConfirmedAtMs),
  });
}

export function trackAssistantOpened(): void {
  journeyId = createSpaJourneyId();
  destinationConfirmedAtMs = null;
  confidentChoiceRecorded = false;
  emittedRecommendationKeys.clear();
  track('assistant_opened');
}

export function trackDestinationSearchStarted(): void {
  track('destination_search_started');
}

export function trackDestinationSearchResultSelected(searchSource: DestinationSearchSource): void {
  track('destination_search_result_selected', { searchSource });
}

export function trackDestinationConfirmed(assistantOrigin: AssistantDestinationOrigin): void {
  destinationConfirmedAtMs = Date.now();
  confidentChoiceRecorded = false;
  track('destination_confirmed', { assistantOrigin });
}

export function trackRecommendationsResponse(response: RecommendationResponse): void {
  const key = `${response.generatedAt}:${response.candidates.length}:${response.partial}:${response.rankingStatus ?? ''}`;
  if (emittedRecommendationKeys.has(key)) return;
  emittedRecommendationKeys.add(key);

  const countBucket = bucketCandidateCount(response.candidates.length);
  const top = response.candidates[0];
  const base: SpaTelemetryParams = {
    candidateCountBucket: countBucket,
    partial: response.partial,
    rankingVersion: response.rankingVersion ?? undefined,
    rankingStatus: response.rankingStatus ?? undefined,
    candidateChannel: top?.channel,
  };

  if (response.candidates.length === 0) {
    track('recommendations_empty', base);
  } else {
    track('recommendations_shown', base);
    if (response.partial) track('recommendations_partial', base);
  }
  if (response.rankingStatus === 'FALLBACK') {
    track('ranking_fallback', base);
  }
}

export function trackRecommendationsFailed(): void {
  track('recommendations_failed');
}

export function trackRecommendationSelected(
  candidate: ParkingCandidate,
  position: number,
): void {
  maybeEmitTimeToConfidentChoice();
  track('recommendation_selected', {
    candidateChannel: candidate.channel,
    recommendationPosition: Math.max(0, Math.min(position, 49)),
    rankingVersion: candidate.rankingVersion ?? undefined,
  });
}

export function trackNavigationStarted(channel?: 'MUNICIPAL_FACILITY' | 'COMMUNITY_SPOT'): void {
  maybeEmitTimeToConfidentChoice();
  track('navigation_started', channel ? { candidateChannel: channel } : undefined);
}

export function trackQuickActionSelected(
  kind: QuickActionKind,
  availability: QuickActionAvailability,
): void {
  track('quick_action_selected', {
    quickActionKind: kind,
    quickActionAvailability: availability,
  });
}

export function trackQuickActionUnavailable(
  kind: QuickActionKind,
  availability: QuickActionAvailability,
): void {
  track('quick_action_unavailable', {
    quickActionKind: kind,
    quickActionAvailability: availability,
  });
}

export function trackParkingSessionStarted(
  originSurface: SpaParkHereOriginSurface,
  targetKind?: RecentParkingTargetKind | null,
): void {
  track('parking_session_started', {
    originSurface,
    ...(targetKind ? { targetKind } : {}),
  });
}

export function trackParkHereFailed(
  failureReason: SpaParkHereFailureReason,
  originSurface: SpaParkHereOriginSurface = 'unknown',
): void {
  track('park_here_failed', { failureReason, originSurface });
}

export function trackRecentParkingRecordFailed(): void {
  track('recent_parking_record_failed');
}

export function trackReturnToCarStarted(): void {
  track('return_to_car_started');
}

export function trackParkingSessionEnded(sessionOutcome: SpaSessionEndOutcome): void {
  track('parking_session_ended', { sessionOutcome });
}
