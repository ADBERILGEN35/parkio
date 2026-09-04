import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  buildQuickActionDescriptors,
  type QuickActionSourceSnapshot,
} from '@parkio/validation';
import { useParkioSdk } from '@/app/AppRuntimeContext';
import { useActiveParkingSessionQuery } from '@/data/hooks/useParkingSessionQueries';
import {
  favouriteDestinationsQueryOptions,
  favouriteParkingQueryOptions,
  recentDestinationsQueryOptions,
  savedPlacesQueryOptions,
} from '@/data/query-options/places';
import { isUsableParkedCoordinate } from '@/components/map/parkedCarCoords';

function queryStatus(
  q: { isPending: boolean; isError: boolean; isSuccess: boolean; fetchStatus: string },
): QuickActionSourceSnapshot['savedPlacesStatus'] {
  if (q.isError) return 'error';
  if (q.isSuccess) return 'success';
  if (q.isPending || q.fetchStatus === 'fetching') return 'pending';
  return 'idle';
}

/**
 * Parallel private sources for SPA Quick Actions (WP-SPA-10).
 * Enabled only when the assistant flag is on and the user is authenticated.
 */
export function useQuickActionSources(options: {
  enabled: boolean;
  authenticated: boolean;
}) {
  const sdk = useParkioSdk();
  const active = options.enabled && options.authenticated;

  const saved = useQuery({
    ...savedPlacesQueryOptions(sdk.placesApi),
    enabled: active,
  });
  const favouriteDestinations = useQuery({
    ...favouriteDestinationsQueryOptions(sdk.placesApi),
    enabled: active,
  });
  const favouriteParking = useQuery({
    ...favouriteParkingQueryOptions(sdk.placesApi),
    enabled: active,
  });
  const recentDestinations = useQuery({
    ...recentDestinationsQueryOptions(sdk.placesApi),
    enabled: active,
  });
  const activeSession = useActiveParkingSessionQuery({ enabled: active });

  const parkedCarAvailable = useMemo(() => {
    const session = activeSession.data;
    if (!session || session.status !== 'ACTIVE') return false;
    return isUsableParkedCoordinate(session.latitude, session.longitude);
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
