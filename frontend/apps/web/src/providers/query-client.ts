import { QueryClient } from '@tanstack/react-query';
import {
  isParkioApiError,
  RateLimitError,
  UserStatusUnavailableError,
} from '@parkio/api-client';

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: (failureCount, error) => {
        if (error instanceof RateLimitError || error instanceof UserStatusUnavailableError) {
          return failureCount < 2;
        }
        if (isParkioApiError(error) && error.status >= 400 && error.status < 500) {
          return false;
        }
        return failureCount < 1;
      },
      staleTime: 30_000,
    },
  },
});
