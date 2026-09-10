import { useQuery } from '@tanstack/react-query';
import { useParkioSdk } from '@/app/AppRuntimeContext';
import {
  favouriteDestinationsQueryOptions,
  recentDestinationsQueryOptions,
  savedPlacesQueryOptions,
} from '../query-options/places';

/** User-owned destination search sources for the assistant (SPA-07/08). */
export function useAssistantDestinationSources(options?: { enabled?: boolean }) {
  const sdk = useParkioSdk();
  const enabled = options?.enabled ?? true;

  const saved = useQuery({
    ...savedPlacesQueryOptions(sdk.placesApi),
    enabled,
  });
  const favourites = useQuery({
    ...favouriteDestinationsQueryOptions(sdk.placesApi),
    enabled,
  });
  const recents = useQuery({
    ...recentDestinationsQueryOptions(sdk.placesApi),
    enabled,
  });

  return { saved, favourites, recents };
}
