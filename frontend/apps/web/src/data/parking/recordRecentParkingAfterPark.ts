import type { ParkedCarTargetRef, ParkingSessionResponse } from '@parkio/types';
import {
  shouldRecordRecentParking,
  toRecordRecentParkingRequest,
} from '@parkio/validation';
import type { QueryClient } from '@tanstack/react-query';
import type { ParkioSdk } from '@/app/sdk';
import { placesKeys } from '@/data/keys';

/**
 * After a successful ParkingSession start, optionally record RecentParking.
 * Fail-open: never rolls back or throws for RecentParking failures.
 * Idempotent server semantics absorb retries; callers should invoke once per success.
 */
export async function recordRecentParkingAfterPark(
  sdk: ParkioSdk,
  queryClient: QueryClient,
  target: ParkedCarTargetRef | null | undefined,
): Promise<'recorded' | 'skipped' | 'failed'> {
  if (!shouldRecordRecentParking(target)) {
    return 'skipped';
  }
  try {
    await sdk.placesApi.recordRecentParking(toRecordRecentParkingRequest(target));
    await queryClient.invalidateQueries({ queryKey: placesKeys.recentParking() });
    return 'recorded';
  } catch {
    return 'failed';
  }
}

/** Identity key to avoid duplicate RecentParking attempts for the same session+target. */
export function recentParkingAttemptKey(
  session: ParkingSessionResponse,
  target: ParkedCarTargetRef,
): string {
  return `${session.id}:${target.kind}:${target.targetId.trim()}`;
}
