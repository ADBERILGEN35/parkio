import { useQuery } from '@tanstack/react-query';
import { activeParkingSessionQueryOptions } from '@/data/query-options/parking';

/** Canonical active ParkingSession query — restored via React Query mount/focus/reconnect. */
export function useActiveParkingSession() {
  return useQuery(activeParkingSessionQueryOptions());
}
