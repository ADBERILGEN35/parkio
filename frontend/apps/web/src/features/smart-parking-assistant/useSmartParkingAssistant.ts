import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import type {
  AssistantDestinationOrigin,
  Destination,
  DestinationSearchItem,
  ParkingCandidate,
} from '@parkio/types';
import { useParkioSdk } from '@/app/AppRuntimeContext';
import { placesKeys } from '@/data/keys';
import { useParkingRecommendationsQuery } from '@/data/hooks/useParkingRecommendationsQuery';
import {
  assistantDestinationIdentityKey,
  type AssistantUrlState,
} from '@/lib/assistantUrlState';
import {
  bindRecommendationEvaluation,
  clearRankingEvaluation,
  recordRankingOutcome,
  setSelectedCandidateById,
} from '@/services/rankingEvaluationCorrelation';
import {
  trackDestinationConfirmed,
  trackDestinationSearchResultSelected,
  trackDestinationSearchStarted,
  trackRecommendationSelected,
  trackRecommendationsFailed,
  trackRecommendationsResponse,
} from '@/services/spaTelemetry';

export type AssistantPhase =
  | 'IDLE'
  | 'SEARCHING'
  | 'DESTINATION_CONFIRMED'
  | 'LOADING_RECOMMENDATIONS'
  | 'RESULTS'
  | 'DEGRADED_RESULTS'
  | 'ERROR'
  | 'CANDIDATE_SELECTED';

export type UseSmartParkingAssistantArgs = {
  enabled: boolean;
  municipalDiscoveryEnabled: boolean;
  urlState: AssistantUrlState;
  onUrlStateChange: (next: AssistantUrlState) => void;
};

/**
 * Headless assistant orchestration for /map (WP-SPA-08/10).
 * Confirmation writes once per explicit selection identity; URL restore does not re-confirm.
 * Quick Actions and search share selectAssistantDestination.
 */
export function useSmartParkingAssistant({
  enabled,
  municipalDiscoveryEnabled,
  urlState,
  onUrlStateChange,
}: UseSmartParkingAssistantArgs) {
  const sdk = useParkioSdk();
  const queryClient = useQueryClient();
  const [searchOpen, setSearchOpen] = useState(false);
  const confirmedWriteKeysRef = useRef<Set<string>>(new Set());

  const destination = enabled ? urlState.destination : null;
  const candidateId = enabled ? urlState.candidateId : null;

  const recommendations = useParkingRecommendationsQuery(destination, {
    enabled: enabled && destination != null,
    includeMunicipal: municipalDiscoveryEnabled,
    includeCommunity: true,
  });

  const confirmMutation = useMutation({
    mutationFn: (dest: Destination) => sdk.placesApi.confirmRecentDestination(dest),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: placesKeys.recentDestinations() });
    },
  });

  const openSearch = useCallback(() => {
    if (!enabled) return;
    setSearchOpen(true);
    trackDestinationSearchStarted();
  }, [enabled]);

  const closeSearch = useCallback(() => {
    setSearchOpen(false);
  }, []);

  const clearDestination = useCallback(() => {
    setSearchOpen(false);
    clearRankingEvaluation();
    onUrlStateChange({ destination: null, candidateId: null });
    confirmMutation.reset();
  }, [confirmMutation, onUrlStateChange]);

  const selectAssistantDestination = useCallback(
    (dest: Destination, origin: AssistantDestinationOrigin = 'SEARCH') => {
      if (!enabled) return;
      const key = assistantDestinationIdentityKey(dest);
      setSearchOpen(false);
      setSelectedCandidateById(null);
      onUrlStateChange({ destination: dest, candidateId: null });

      if (!confirmedWriteKeysRef.current.has(key)) {
        confirmedWriteKeysRef.current.add(key);
        trackDestinationConfirmed(origin);
        confirmMutation.mutate(dest, {
          onError: () => {
            confirmedWriteKeysRef.current.delete(key);
          },
        });
      }
    },
    [confirmMutation, enabled, onUrlStateChange],
  );

  const confirmDestination = useCallback(
    (item: DestinationSearchItem) => {
      trackDestinationSearchResultSelected(item.source);
      selectAssistantDestination(item.destination, 'SEARCH');
    },
    [selectAssistantDestination],
  );

  const selectCandidate = useCallback(
    (candidate: ParkingCandidate | null) => {
      if (!destination) return;
      if (candidate && recommendations.data) {
        const position = recommendations.data.candidates.findIndex((c) => c.id === candidate.id);
        const ordinal = position >= 0 ? position : 0;
        trackRecommendationSelected(candidate, ordinal);
        setSelectedCandidateById(candidate.id);
        recordRankingOutcome({
          outcomeType: 'RECOMMENDATION_SELECTED',
          candidateId: candidate.id,
          candidateOrdinal: ordinal,
          platform: 'WEB',
        });
      } else {
        setSelectedCandidateById(null);
      }
      onUrlStateChange({
        destination,
        candidateId: candidate?.id ?? null,
      });
    },
    [destination, onUrlStateChange, recommendations.data],
  );

  const selectCandidateById = useCallback(
    (id: string | null) => {
      if (!destination) return;
      if (id && recommendations.data) {
        const position = recommendations.data.candidates.findIndex((c) => c.id === id);
        const candidate = position >= 0 ? recommendations.data.candidates[position] : null;
        if (candidate) {
          trackRecommendationSelected(candidate, position);
          setSelectedCandidateById(candidate.id);
          recordRankingOutcome({
            outcomeType: 'RECOMMENDATION_SELECTED',
            candidateId: candidate.id,
            candidateOrdinal: position,
            platform: 'WEB',
          });
        } else {
          setSelectedCandidateById(null);
        }
      } else {
        setSelectedCandidateById(null);
      }
      onUrlStateChange({ destination, candidateId: id });
    },
    [destination, onUrlStateChange, recommendations.data],
  );

  // Drop search UI when assistant disabled.
  useEffect(() => {
    if (!enabled) {
      setSearchOpen(false);
    }
  }, [enabled]);

  // Emit recommendation funnel events once per response identity (not per render).
  const lastRecKeyRef = useRef<string | null>(null);
  const lastRecErrorRef = useRef(false);
  useEffect(() => {
    if (!enabled || !destination) return;
    if (recommendations.isError) {
      if (!lastRecErrorRef.current) {
        lastRecErrorRef.current = true;
        trackRecommendationsFailed();
      }
      return;
    }
    lastRecErrorRef.current = false;
    const data = recommendations.data;
    if (!data) return;
    const key = `${data.generatedAt}:${data.candidates.length}:${data.partial}:${data.rankingStatus ?? ''}:${data.evaluationId ?? ''}`;
    if (lastRecKeyRef.current === key) return;
    lastRecKeyRef.current = key;
    bindRecommendationEvaluation(data);
    if (candidateId) {
      setSelectedCandidateById(candidateId);
    }
    trackRecommendationsResponse(data);
  }, [
    candidateId,
    destination,
    enabled,
    recommendations.data,
    recommendations.isError,
  ]);

  const selectedCandidate = useMemo(() => {
    if (!candidateId || !recommendations.data) return null;
    return recommendations.data.candidates.find((c) => c.id === candidateId) ?? null;
  }, [candidateId, recommendations.data]);

  const recommendedRefIds = useMemo(() => {
    const set = new Set<string>();
    for (const c of recommendations.data?.candidates ?? []) {
      set.add(c.refId);
    }
    return set;
  }, [recommendations.data?.candidates]);

  const phase: AssistantPhase = useMemo(() => {
    if (!enabled) return 'IDLE';
    if (searchOpen && !destination) return 'SEARCHING';
    if (!destination) return searchOpen ? 'SEARCHING' : 'IDLE';
    if (recommendations.isError) return 'ERROR';
    if (recommendations.isFetching && !recommendations.data) return 'LOADING_RECOMMENDATIONS';
    if (selectedCandidate) return 'CANDIDATE_SELECTED';
    if (recommendations.data?.partial) return 'DEGRADED_RESULTS';
    if (recommendations.data) return 'RESULTS';
    return 'DESTINATION_CONFIRMED';
  }, [
    destination,
    enabled,
    recommendations.data,
    recommendations.isError,
    recommendations.isFetching,
    searchOpen,
    selectedCandidate,
  ]);

  return {
    enabled,
    phase,
    searchOpen,
    openSearch,
    closeSearch,
    destination,
    candidateId,
    selectedCandidate,
    recommendedRefIds,
    confirmDestination,
    selectAssistantDestination,
    clearDestination,
    selectCandidate,
    selectCandidateById,
    recommendations,
    confirmMutation,
  };
}
