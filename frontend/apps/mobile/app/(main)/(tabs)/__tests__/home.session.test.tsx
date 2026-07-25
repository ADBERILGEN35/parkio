import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react-native';
import type { ReactNode } from 'react';
import type { Profile, User, UserStats } from '@parkio/types';
import HomeScreen from '../home';
import ProfileScreen from '../profile';
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

jest.mock('@/providers/ToastProvider', () => ({
  useToast: () => ({ showError: jest.fn() }),
}));

jest.mock('expo-router', () => {
  const React = require('react');
  const { Text } = require('react-native');
  return {
    useRouter: () => ({ push: jest.fn() }),
    Redirect: ({ href }: { href: string }) => React.createElement(Text, null, `redirect:${href}`),
  };
});

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
    client.setQueryData(['me', 'stats'], statsB);

    act(() => {
      useAuthStore.getState().clearSession();
    });
    await waitFor(() => {
      expect(client.getQueryData(['me', 'profile'])).toBeUndefined();
      expect(client.getQueryData(['me', 'stats'])).toBeUndefined();
    });

    mockedUsersApi.getMyProfile.mockResolvedValue(profileB);
    mockedUsersApi.getMyStats.mockResolvedValue(statsB);

    act(() => {
      useAuthStore.getState().setSession(userB);
    });

    const { findByText } = renderWithProviders(
      <QueryHarness client={client}>
        <ProfileScreen />
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
        <ProfileScreen />
      </QueryHarness>,
    );

    await waitFor(() => {
      expect(client.getQueryData(['me', 'profile'])).toBeUndefined();
    });
    expect(queryByText('Alice')).toBeNull();
    expect(await findByText('Bob')).toBeTruthy();
  });
});

// The account-switch invariant is asserted against the screen that renders the
// signed-in identity today. The legacy Home dashboard was replaced by a redirect
// to Map, so identity display moved to Profile.
describe('Signed-in identity after account switch', () => {
  it('never shows the previous user name while the next user loads', async () => {
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
        <ProfileScreen />
      </QueryHarness>,
    );

    expect(queryByText('Alice')).toBeNull();
    expect(await findByText('Bob')).toBeTruthy();
    expect(queryByText('Alice')).toBeNull();
  });

  it('keeps the Home tab as a redirect to Map', () => {
    const client = createClient();
    useAuthStore.getState().setSession(userB);

    const { getByText } = renderWithProviders(
      <QueryHarness client={client}>
        <HomeScreen />
      </QueryHarness>,
    );

    expect(getByText('redirect:/(main)/(tabs)/map')).toBeTruthy();
  });
});
