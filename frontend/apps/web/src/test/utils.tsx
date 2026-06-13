import type { User } from '@parkio/types';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import type { ReactNode } from 'react';
import { MemoryRouter } from 'react-router-dom';
import { useAuthStore } from '@/auth/store';

/** Fresh client per test — no retries, so error states are deterministic and fast. */
export function createTestQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
}

export function renderWithProviders(ui: ReactNode, { initialEntries = ['/'] } = {}) {
  const queryClient = createTestQueryClient();
  const result = render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={initialEntries}>{ui}</MemoryRouter>
    </QueryClientProvider>,
  );
  return { ...result, queryClient };
}

export function resetAuth() {
  useAuthStore.getState().clearSession();
}

export function signInAs(roles: string[]) {
  const user: User = {
    id: '6f9619ff-8b86-4d01-b42d-00cf4fc964ff',
    email: 'tester@parkio.dev',
    status: 'ACTIVE',
    roles,
  };
  useAuthStore.getState().setSession('test-access-token', 'test-refresh-token', user);
  return user;
}
