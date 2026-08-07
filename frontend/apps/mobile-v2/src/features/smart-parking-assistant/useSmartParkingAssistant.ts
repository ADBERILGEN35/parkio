import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { DestinationSearchItem, ParkingCandidate } from '@parkio/types';
import { placesKeys } from '@/data/keys';
import { recommendationsQueryOptions } from '@/data/query-options/recommendations';
import { placesApi, parkingApi } from '@/services/api';
import { assistantDestinationIdentityKey } from './assistantDestinationKey';
import { useAssistantDestinationStore } from './assistantStore';

export type AssistantPhase =
  | 'IDLE'
  | 'SEARCHING'
  | 'DESTINATION_CONFIRMED'
  | 'LOADING_RECOMMENDATIONS'
  | 'RESULTS'
  | 'DEGRADED_RESULTS'
  | 'CANDIDATE_SELECTED'
  | 'ERROR';

export type UseSmartParkingAssistantArgs = {
  enabled: boolean;
  municipalDiscoveryEnabled: boolean;
};

/**
 * Headless assistant orchestration for mobile-v2 MapScreen (WP-SPA-09).
 * confirmRecentDestination runs once per explicit selection identity;
 * hydrated destination does not re-confirm.
 */
export function useSmartParkingAssistant({
  enabled,
  municipalDiscoveryEnabled,
}: UseSmartParkingAssistantArgs) {
  const queryClient = useQueryClient();
  const [searchOpen, setSearchOpen] = useState(false);
  const [candidateId, setCandidateId] = useState<string | null>(null);
  const confirmedWriteKeysRef = useRef<Set<string>>(new Set());

  const hydrated = useAssistantDestinationStore((s) => s.hydrated);
  const storedDestination = useAssistantDestinationStore((s) => s.destination);
  const setStoredDestination = useAssistantDestinationStore((s) => s.setDestination);
  const clearStoredDestination = useAssistantDestinationStore((s) => s.clearDestination);
  const hydrateStore = useAssistantDestinationStore((s) => s.hydrate);

  useEffect(() => {
    if (enabled && !hydrated) {
      void hydrateStore();
    }
  }, [enabled, hydrated, hydrateStore]);

  const destination = enabled ? storedDestination : null;

  const recommendations = useQuery({
    ...recommendationsQueryOptions(parkingApi, {
      destination: destination ?? {
        label: '_',
        latitude: 0,
        longitude: 0,
        source: 'GEOCODING',
      },
      includeMunicipal: municipalDiscoveryEnabled,
      includeCommunity: true,
    }),
    enabled: enabled && destination != null,
  });

  const confirmMutation = useMutation({
    mutationFn: (dest: NonNullable<typeof destination>) =>
      placesApi.confirmRecentDestination(dest),
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
    setCandidateId(null);
    clearStoredDestination();
    confirmMutation.reset();
  }, [clearStoredDestination, confirmMutation]);

  const confirmDestination = useCallback(
    (item: DestinationSearchItem) => {
      if (!enabled) return;
      const dest = item.destination;
      const key = assistantDestinationIdentityKey(dest);
      setSearchOpen(false);
      setCandidateId(null);
      setStoredDestination(dest);

      if (!confirmedWriteKeysRef.current.has(key)) {
        confirmedWriteKeysRef.current.add(key);
        confirmMutation.mutate(dest, {
          onError: () => {
            confirmedWriteKeysRef.current.delete(key);
          },
        });
      }
    },
    [confirmMutation, enabled, setStoredDestination],
  );

  const selectCandidate = useCallback((candidate: ParkingCandidate | null) => {
    setCandidateId(candidate?.id ?? null);
  }, []);

  const selectCandidateById = useCallback((id: string | null) => {
    setCandidateId(id);
  }, []);

  // Derive session UI from flag + query payload — avoid effect-driven setState resets.
  const effectiveSearchOpen = enabled && searchOpen;

  const selectedCandidate = useMemo(() => {
    if (!enabled || !candidateId || !recommendations.data) return null;
    return recommendations.data.candidates.find((c) => c.id === candidateId) ?? null;
  }, [candidateId, enabled, recommendations.data]);

  const effectiveCandidateId = selectedCandidate?.id ?? null;

  const recommendedCommunityIds = useMemo(() => {
    const ids: string[] = [];
    for (const c of recommendations.data?.candidates ?? []) {
      if (c.channel === 'COMMUNITY_SPOT') ids.push(c.refId);
    }
    return ids;
  }, [recommendations.data?.candidates]);

  const recommendedMunicipalIds = useMemo(() => {
    const ids: string[] = [];
    for (const c of recommendations.data?.candidates ?? []) {
      if (c.channel === 'MUNICIPAL_FACILITY') ids.push(c.refId);
    }
    return ids;
  }, [recommendations.data?.candidates]);

  const topCandidate = recommendations.data?.candidates?.[0] ?? null;

  const phase: AssistantPhase = useMemo(() => {
    if (!enabled) return 'IDLE';
    if (effectiveSearchOpen && !destination) return 'SEARCHING';
    if (!destination) return effectiveSearchOpen ? 'SEARCHING' : 'IDLE';
    if (recommendations.isError) return 'ERROR';
    if (recommendations.isFetching && !recommendations.data) return 'LOADING_RECOMMENDATIONS';
    if (selectedCandidate) return 'CANDIDATE_SELECTED';
    if (recommendations.data?.partial) return 'DEGRADED_RESULTS';
    if (recommendations.data) return 'RESULTS';
    return 'DESTINATION_CONFIRMED';
  }, [
    destination,
    effectiveSearchOpen,
    enabled,
    recommendations.data,
    recommendations.isError,
    recommendations.isFetching,
    selectedCandidate,
  ]);

  return {
    enabled,
    phase,
    hydrated: !enabled || hydrated,
    searchOpen: effectiveSearchOpen,
    openSearch,
    closeSearch,
    destination,
    candidateId: effectiveCandidateId,
    selectedCandidate,
    recommendedCommunityIds,
    recommendedMunicipalIds,
    topCandidate,
    confirmDestination,
    clearDestination,
    selectCandidate,
    selectCandidateById,
    recommendations,
    confirmMutation,
  };
}
