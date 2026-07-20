import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react-native';
import type { ReactNode } from 'react';
import type { Profile, User } from '@parkio/types';
import HomeScreen from '../home';
import { SessionQueryCacheSync } from '@/providers/SessionQueryCacheSync';
import { useAuthStore } from '@/state/authStore';
import { renderWithProviders } from '@/test/renderWithProviders';

jest.mock('expo-router', () => ({
  Redirect: () => null,
  useRouter: () => ({ push: jest.fn() }),
}));

const userA: User = { id: 'user-a', email: 'alice@parkio.dev', status: 'ACTIVE', roles: ['USER'] };
const userB: User = { id: 'user-b', email: 'bob@parkio.dev', status: 'ACTIVE', roles: ['USER'] };

const profileA: Profile = {
  id: 'profile-a',
  authUserId: 'user-a',
  email: 'alice@parkio.dev',
  displayName: 'Alice',
  phoneNumber: null,
  city: null,
  status: 'ACTIVE',
  createdAt: '2026-01-01T00:00:00.000Z',
};

function createClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: Infinity } },
  });
}

function QueryHarness({ client, children }: { client: QueryClient; children: ReactNode }) {
  return (
    <QueryClientProvider client={client}>
      <SessionQueryCacheSync client={client} />
      {children}
    </QueryClientProvider>
  );
}

beforeEach(() => {
  useAuthStore.setState({
    user: null,
    roles: [],
    status: null,
    isAuthenticated: false,
    suspended: false,
    bootstrapPending: false,
    sessionEpoch: 0,
  });
  jest.clearAllMocks();
});

describe('SessionQueryCacheSync', () => {
  it('clears stale cache on logout and loads the next user', async () => {
    const client = createClient();
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryHarness client={client}>{children}</QueryHarness>
    );

    renderHook(() => null, { wrapper });

    act(() => {
      useAuthStore.getState().setSession(userA);
    });
    client.setQueryData(['me', 'profile'], profileA);

    act(() => {
      useAuthStore.getState().clearSession();
    });
    await waitFor(() => {
      expect(client.getQueryData(['me', 'profile'])).toBeUndefined();
    });

    act(() => {
      useAuthStore.getState().setSession(userB);
    });

    const { queryByText } = renderWithProviders(
      <QueryHarness client={client}>
        <HomeScreen />
      </QueryHarness>,
    );

    expect(queryByText('Alice')).toBeNull();
    expect(queryByText('Bob')).toBeNull();
  });

  it('clears mismatched profile cache on first mount after account switch', async () => {
    const client = createClient();
    client.setQueryData(['me', 'profile'], profileA);

    useAuthStore.getState().setSession(userB);
    const { queryByText } = renderWithProviders(
      <QueryHarness client={client}>
        <HomeScreen />
      </QueryHarness>,
    );

    await waitFor(() => {
      expect(client.getQueryData(['me', 'profile'])).toBeUndefined();
    });
    expect(queryByText('Alice')).toBeNull();
    expect(queryByText('Bob')).toBeNull();
  });
});

describe('HomeScreen session consistency', () => {
  it('always redirects legacy home to the map tab', () => {
    const client = createClient();
    client.setQueryData(['me', 'profile'], profileA);

    useAuthStore.getState().setSession(userB);

    const { queryByText } = renderWithProviders(
      <QueryHarness client={client}>
        <HomeScreen />
      </QueryHarness>,
    );

    expect(queryByText('Alice')).toBeNull();
    expect(queryByText('Bob')).toBeNull();
  });
});