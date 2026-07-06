import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react-native';
import type { ReactNode } from 'react';
import type { Profile, User, UserStats } from '@parkio/types';
import HomeScreen from '../home';
import { SessionQueryCacheSync } from '@/providers/SessionQueryCacheSync';
import { usersApi } from '@/services/api';
import { useAuthStore } from '@/state/authStore';
import { renderWithProviders } from '@/test/renderWithProviders';

jest.mock('@/services/api', () => ({
  usersApi: {
    getMyProfile: jest.fn(),
    getMyStats: jest.fn(),
  },
}));

jest.mock('expo-router', () => ({
  useRouter: () => ({ push: jest.fn() }),
}));

const mockedUsersApi = usersApi as jest.Mocked<typeof usersApi>;

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

const profileB: Profile = {
  id: 'profile-b',
  authUserId: 'user-b',
  email: 'bob@parkio.dev',
  displayName: 'Bob',
  phoneNumber: null,
  city: null,
  status: 'ACTIVE',
  createdAt: '2026-01-01T00:00:00.000Z',
};

const statsB: UserStats = {
  trustScore: 88,
  trustBand: 'HIGH_TRUST',
  totalPoints: 420,
  currentLevel: 3,
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

    mockedUsersApi.getMyProfile.mockResolvedValue(profileB);
    mockedUsersApi.getMyStats.mockResolvedValue(statsB);

    act(() => {
      useAuthStore.getState().setSession(userB);
    });

    const { findByText } = renderWithProviders(
      <QueryHarness client={client}>
        <HomeScreen />
      </QueryHarness>,
    );

    expect(await findByText('Bob')).toBeTruthy();
  });

  it('clears mismatched profile cache on first mount after account switch', async () => {
    const client = createClient();
    client.setQueryData(['me', 'profile'], profileA);

    useAuthStore.getState().setSession(userB);
    mockedUsersApi.getMyProfile.mockResolvedValue(profileB);
    mockedUsersApi.getMyStats.mockResolvedValue(statsB);

    const { findByText, queryByText } = renderWithProviders(
      <QueryHarness client={client}>
        <HomeScreen />
      </QueryHarness>,
    );

    await waitFor(() => {
      expect(client.getQueryData(['me', 'profile'])).toBeUndefined();
    });
    expect(queryByText('Alice')).toBeNull();
    expect(await findByText('Bob')).toBeTruthy();
  });
});

describe('HomeScreen session consistency', () => {
  it('never shows the previous user name while user B stats are visible', async () => {
    const client = createClient();
    client.setQueryData(['me', 'profile'], profileA);
    client.setQueryData(['me', 'stats'], statsB);

    useAuthStore.getState().setSession(userB);
    mockedUsersApi.getMyProfile.mockImplementation(
      () => new Promise((resolve) => setTimeout(() => resolve(profileB), 50)),
    );
    mockedUsersApi.getMyStats.mockResolvedValue(statsB);

    const { queryByText, findByText } = renderWithProviders(
      <QueryHarness client={client}>
        <HomeScreen />
      </QueryHarness>,
    );

    expect(queryByText('Alice')).toBeNull();
    expect(await findByText('Bob')).toBeTruthy();
    expect(await findByText('420')).toBeTruthy();
  });
});