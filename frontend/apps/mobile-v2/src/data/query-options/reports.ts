import { queryOptions } from '@tanstack/react-query';
import { moderationApi } from '@/services/api';
import { reportsKeys } from '../keys';

export function myReportsQueryOptions() {
  return queryOptions({
    queryKey: reportsKeys.all,
    queryFn: ({ signal }) => moderationApi.getMyReports({ signal }),
  });
}
