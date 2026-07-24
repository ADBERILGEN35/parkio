import type { QueryClient } from '@tanstack/react-query';
import type { PublicSpot, Spot } from '@parkio/types';
import { parkingKeys } from '@/data/keys';
import { invalidateGamificationQueries } from '@/lib/gamificationCache';

/**
 * Canonical cache write after verify/claim (and any mutation that returns the
 * authoritative PublicSpot). Patches detail + nearby lists in place, and mirrors
 * overlapping fields onto my-spots entries when present.
 */
export function applyParkingSpotUpdate(queryClient: QueryClient, updated: PublicSpot): void {
  queryClient.setQueryData(parkingKeys.spot(updated.id), updated);
  queryClient.setQueriesData<PublicSpot[]>({ queryKey: parkingKeys.nearbyRoot() }, (current) =>
    current?.map((item) => (item.id === updated.id ? updated : item)),
  );
  queryClient.setQueryData<Spot[]>(parkingKeys.mySpots(), (current) =>
    current?.map((item) => (item.id === updated.id ? { ...item, ...updated } : item)),
  );
}

/**
 * After create: membership of my-spots / nearby changed, and gamification events
 * land asynchronously — invalidate rather than inventing optimistic list rows.
 */
export async function syncAfterSpotCreate(queryClient: QueryClient): Promise<void> {
  await queryClient.invalidateQueries({ queryKey: parkingKeys.mySpots() });
  await queryClient.invalidateQueries({ queryKey: parkingKeys.nearbyRoot() });
  await invalidateGamificationQueries(queryClient);
}

/**
 * After verify/claim: entity is already patched; my-spots membership / owner
 * metrics and gamification views need a targeted refresh.
 */
export async function syncAfterSpotLifecycleMutation(queryClient: QueryClient): Promise<void> {
  await queryClient.invalidateQueries({ queryKey: parkingKeys.mySpots() });
  await invalidateGamificationQueries(queryClient);
}