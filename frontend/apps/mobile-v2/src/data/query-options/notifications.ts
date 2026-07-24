import { queryOptions } from '@tanstack/react-query';
import { notificationsApi } from '@/services/api';
import { notificationsKeys } from '../keys';

export function myNotificationsQueryOptions() {
  return queryOptions({
    queryKey: notificationsKeys.all,
    queryFn: ({ signal }) => notificationsApi.getMyNotifications({ signal }),
  });
}
