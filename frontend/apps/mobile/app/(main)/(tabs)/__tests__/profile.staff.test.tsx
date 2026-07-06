import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import ProfileScreen from '../profile';
import { usersApi } from '@/services/api';
import { useAuthStore } from '@/state/authStore';
import { renderWithProviders } from '@/test/renderWithProviders';

jest.mock('expo-router', () => ({
  useRouter: () => ({ push: jest.fn() }),
}));

jest.mock('@/providers/ToastProvider', () => ({
  useToast: () => ({ showError: jest.fn() }),
}));

jest.mock('@/services/api', () => ({
  usersApi: {
    getMyProfile: jest.fn(),
  },
}));

const mockedUsersApi = usersApi as jest.Mocked<typeof usersApi>;

function renderProfile() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: Infinity } },
  });
  return renderWithProviders(
    <QueryClientProvider client={queryClient}>
      <ProfileScreen />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  jest.clearAllMocks();
  useAuthStore.setState({
    user: { id: 'u1', email: 'user@example.com', status: 'ACTIVE', roles: ['USER'] },
    roles: ['USER'],
    status: 'ACTIVE',
    isAuthenticated: true,
    suspended: false,
    bootstrapPending: false,
    sessionEpoch: 0,
  });
  mockedUsersApi.getMyProfile.mockResolvedValue({
    id: 'p1',
    authUserId: 'u1',
    email: 'user@example.com',
    displayName: 'User',
    phoneNumber: null,
    city: null,
    status: 'ACTIVE',
    createdAt: '2026-01-01T00:00:00.000Z',
  });
});

describe('Profile staff entries', () => {
  it('hides staff links for ordinary users', async () => {
    const { queryByTestId, findByTestId } = renderProfile();
    expect(await findByTestId('profile.logout')).toBeTruthy();
    expect(queryByTestId('profile.moderation')).toBeNull();
    expect(queryByTestId('profile.analytics')).toBeNull();
  });

  it('shows moderation but not analytics for moderators', async () => {
    useAuthStore.setState({
      user: { id: 'm1', email: 'mod@example.com', status: 'ACTIVE', roles: ['MODERATOR'] },
      roles: ['MODERATOR'],
    });

    const { queryByTestId, findByTestId } = renderProfile();
    expect(await findByTestId('profile.moderation')).toBeTruthy();
    expect(queryByTestId('profile.analytics')).toBeNull();
  });

  it('shows moderation and analytics for admins', async () => {
    useAuthStore.setState({
      user: { id: 'a1', email: 'admin@example.com', status: 'ACTIVE', roles: ['ADMIN'] },
      roles: ['ADMIN'],
    });

    const { findByTestId } = renderProfile();
    expect(await findByTestId('profile.moderation')).toBeTruthy();
    expect(await findByTestId('profile.analytics')).toBeTruthy();
  });
});