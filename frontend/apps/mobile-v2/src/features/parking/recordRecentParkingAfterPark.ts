import type { ParkedCarTargetRef, ParkingSessionResponse } from '@parkio/types';
import {
  shouldRecordRecentParking,
  toRecordRecentParkingRequest,
} from '@parkio/validation';
import type { QueryClient } from '@tanstack/react-query';
import { placesKeys } from '@/data/keys';
import { placesApi } from '@/services/api';

/**
 * Fail-open RecentParking recording after a successful ParkingSession start.
 */
export async function recordRecentParkingAfterPark(
  queryClient: QueryClient,
  target: ParkedCarTargetRef | null | undefined,
): Promise<'recorded' | 'skipped' | 'failed'> {
  if (!shouldRecordRecentParking(target)) {
    return 'skipped';
  }
  try {
    await placesApi.recordRecentParking(toRecordRecentParkingRequest(target));
    await queryClient.invalidateQueries({ queryKey: placesKeys.recentParking() });
    return 'recorded';
  } catch {
    return 'failed';
  }
}

export function recentParkingAttemptKey(
  session: ParkingSessionResponse,
  target: ParkedCarTargetRef,
): string {
  return `${session.id}:${target.kind}:${target.targetId.trim()}`;
}
