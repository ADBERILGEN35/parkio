import type { QueryClient } from '@tanstack/react-query';
import type { ParkingSessionResponse } from '@parkio/types';
import { parkingKeys } from '@/data/keys';

/**
 * Canonical cache writes for ParkingSession server state. Keeps the active-session
 * entry and terminal history in sync after mutations without duplicating the
 * key hierarchy across hooks/components.
 */

/** Set (or clear with `null`) the authoritative active-session cache entry. */
export function setActiveParkingSession(
  queryClient: QueryClient,
  session: ParkingSessionResponse | null,
): void {
  queryClient.setQueryData(parkingKeys.activeSession(), session);
}

/** Refresh terminal history after a session becomes terminal or is deleted. */
export async function invalidateParkingSessionHistory(queryClient: QueryClient): Promise<void> {
  await queryClient.invalidateQueries({ queryKey: parkingKeys.sessionHistoryRoot() });
}

/**
 * After a terminal transition (complete/cancel): a non-ACTIVE result proves the
 * session left the active window, so clear the active entry set-only (no stale
 * refetch race) and refresh history. An unexpected ACTIVE payload is left for the
 * caller to reconcile from server truth.
 */
export async function syncAfterParkingSessionTerminal(
  queryClient: QueryClient,
  result: ParkingSessionResponse,
): Promise<void> {
  if (result.status !== 'ACTIVE') {
    setActiveParkingSession(queryClient, null);
  }
  await invalidateParkingSessionHistory(queryClient);
}
