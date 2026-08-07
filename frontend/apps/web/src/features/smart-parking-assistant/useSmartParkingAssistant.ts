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
  }, [enabled]);

  const closeSearch = useCallback(() => {
    setSearchOpen(false);
  }, []);

  const clearDestination = useCallback(() => {
    setSearchOpen(false);
    onUrlStateChange({ destination: null, candidateId: null });
    confirmMutation.reset();
  }, [confirmMutation, onUrlStateChange]);

  const selectAssistantDestination = useCallback(
    (dest: Destination, origin: AssistantDestinationOrigin = 'SEARCH') => {
      if (!enabled) return;
      void origin;
      const key = assistantDestinationIdentityKey(dest);
      setSearchOpen(false);
      onUrlStateChange({ destination: dest, candidateId: null });

      if (!confirmedWriteKeysRef.current.has(key)) {
        confirmedWriteKeysRef.current.add(key);
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
      selectAssistantDestination(item.destination, 'SEARCH');
    },
    [selectAssistantDestination],
  );

  const selectCandidate = useCallback(
    (candidate: ParkingCandidate | null) => {
      if (!destination) return;
      onUrlStateChange({
        destination,
        candidateId: candidate?.id ?? null,
      });
    },
    [destination, onUrlStateChange],
  );

  const selectCandidateById = useCallback(
    (id: string | null) => {
      if (!destination) return;
      onUrlStateChange({ destination, candidateId: id });
    },
    [destination, onUrlStateChange],
  );

  // Drop search UI when assistant disabled.
  useEffect(() => {
    if (!enabled) {
      setSearchOpen(false);
    }
  }, [enabled]);

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
