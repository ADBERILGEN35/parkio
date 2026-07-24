import { queryOptions } from '@tanstack/react-query';
import type { ParkioSdk } from '@/app/sdk';
import { reportsKeys } from '@/data/keys';

export function myReportsQueryOptions(sdk: ParkioSdk) {
  return queryOptions({
    queryKey: reportsKeys.all,
    queryFn: () => sdk.moderationApi.getMyReports(),
  });
}