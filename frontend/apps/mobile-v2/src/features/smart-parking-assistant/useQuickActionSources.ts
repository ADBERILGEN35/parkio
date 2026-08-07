import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  buildQuickActionDescriptors,
  type QuickActionSourceSnapshot,
} from '@parkio/validation';
import {
  favouriteDestinationsQueryOptions,
  favouriteParkingQueryOptions,
  recentDestinationsQueryOptions,
  savedPlacesQueryOptions,
} from '@/data/query-options/places';
import { useActiveParkingSession } from '@/features/parking/useActiveParkingSession';
import { isValidParkingDestination } from '@/features/parking/parkingLocationLinks';
import { useAuthStore } from '@/state/authStore';

function queryStatus(q: {
  isPending: boolean;
  isError: boolean;
  isSuccess: boolean;
  fetchStatus: string;
}): QuickActionSourceSnapshot['savedPlacesStatus'] {
  if (q.isError) return 'error';
  if (q.isSuccess) return 'success';
  if (q.isPending || q.fetchStatus === 'fetching') return 'pending';
  return 'idle';
}

export function useQuickActionSources(options: { enabled: boolean }) {
  const userId = useAuthStore((s) => s.user?.id ?? null);
  const active = options.enabled && Boolean(userId);

  const saved = useQuery({ ...savedPlacesQueryOptions(), enabled: active });
  const favouriteDestinations = useQuery({
    ...favouriteDestinationsQueryOptions(),
    enabled: active,
  });
  const favouriteParking = useQuery({
    ...favouriteParkingQueryOptions(),
    enabled: active,
  });
  const recentDestinations = useQuery({
    ...recentDestinationsQueryOptions(),
    enabled: active,
  });
  const activeSession = useActiveParkingSession();

  const parkedCarAvailable = useMemo(() => {
    const session = activeSession.data;
    if (!session || session.status !== 'ACTIVE') return false;
    return isValidParkingDestination(session.latitude, session.longitude);
  }, [activeSession.data]);

  const snapshot: QuickActionSourceSnapshot = {
    savedPlaces: saved.data ?? null,
    savedPlacesStatus: active ? queryStatus(saved) : 'idle',
    favouriteDestinations: favouriteDestinations.data ?? null,
    favouriteDestinationsStatus: active ? queryStatus(favouriteDestinations) : 'idle',
    favouriteParkingCount: favouriteParking.data?.length ?? null,
    favouriteParkingStatus: active ? queryStatus(favouriteParking) : 'idle',
    recentDestinations: recentDestinations.data ?? null,
    recentDestinationsStatus: active ? queryStatus(recentDestinations) : 'idle',
    parkedCarAvailable,
    parkedCarStatus: !active
      ? 'disabled'
      : activeSession.isError
        ? 'error'
        : activeSession.isPending
          ? 'pending'
          : 'success',
  };

  const descriptors = buildQuickActionDescriptors(snapshot);

  return {
    snapshot,
    descriptors,
    saved,
    favouriteDestinations,
    favouriteParking,
    recentDestinations,
    activeSession,
    parkedCarAvailable,
  };
}
