import { queryOptions } from '@tanstack/react-query';
import { gamificationApi } from '@/services/api';
import { gamificationKeys } from '../keys';

export function accessPolicyQueryOptions() {
  return queryOptions({
    queryKey: gamificationKeys.accessPolicy(),
    queryFn: ({ signal }) => gamificationApi.getMyAccessPolicy({ signal }),
    staleTime: 60_000,
  });
}

export function myLevelQueryOptions() {
  return queryOptions({
    queryKey: gamificationKeys.level(),
    queryFn: ({ signal }) => gamificationApi.getMyLevel({ signal }),
  });
}

export function myPointsQueryOptions() {
  return queryOptions({
    queryKey: gamificationKeys.points(),
    queryFn: ({ signal }) => gamificationApi.getMyPoints({ signal }),
  });
}

export function myProgressQueryOptions() {
  return queryOptions({
    queryKey: gamificationKeys.progress(),
    queryFn: ({ signal }) => gamificationApi.getMyProgress({ signal }),
  });
}

export function levelsQueryOptions() {
  return queryOptions({
    queryKey: gamificationKeys.levels(),
    queryFn: ({ signal }) => gamificationApi.getLevels({ signal }),
    staleTime: 10 * 60_000,
  });
}

export function leaderboardQueryOptions(limit?: number) {
  return queryOptions({
    queryKey: gamificationKeys.leaderboard(limit),
    queryFn: ({ signal }) => gamificationApi.getLeaderboard(limit, { signal }),
  });
}
