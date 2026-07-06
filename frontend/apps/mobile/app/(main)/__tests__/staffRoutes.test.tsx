import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactElement } from 'react';
import { analyticsApi, moderationApi } from '@/services/api';
import { useAuthStore } from '@/state/authStore';
import { renderWithProviders } from '@/test/renderWithProviders';
import AnalyticsScreen from '../analytics';
import ModerationScreen from '../moderation';

jest.mock('expo-router', () => ({
  Stack: {
    Screen: () => null,
  },
}));

jest.mock('@/services/api', () => ({
  analyticsApi: {
    getAnalyticsOverview: jest.fn(),
    getParkingAnalytics: jest.fn(),
    getAnalyticsMetrics: jest.fn(),
    getDailyAnalytics: jest.fn(),
  },
  moderationApi: {
    getModerationCases: jest.fn(),
    getModerationAppeals: jest.fn(),
  },
}));

const mockedAnalyticsApi = analyticsApi as jest.Mocked<typeof analyticsApi>;
const mockedModerationApi = moderationApi as jest.Mocked<typeof moderationApi>;

function renderScreen(ui: ReactElement) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: Infinity } },
  });
  return renderWithProviders(<QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>);
}

beforeEach(() => {
  jest.clearAllMocks();
  useAuthStore.setState({
    user: null,
    roles: [],
    status: null,
    isAuthenticated: false,
    suspended: false,
    bootstrapPending: false,
    sessionEpoch: 0,
  });
});

describe('mobile staff route guards', () => {
  it('blocks direct moderation route access for non-privileged users before fetching staff data', () => {
    useAuthStore.setState({
      user: {
        id: 'user-1',
        email: 'user@example.com',
        roles: ['USER'],
        status: 'ACTIVE',
      },
      roles: ['USER'],
      isAuthenticated: true,
    });

    const { getByText } = renderScreen(<ModerationScreen />);

    expect(getByText('Access denied')).toBeTruthy();
    expect(mockedModerationApi.getModerationCases).not.toHaveBeenCalled();
    expect(mockedModerationApi.getModerationAppeals).not.toHaveBeenCalled();
  });

  it('blocks direct analytics route access for non-privileged users before fetching staff data', () => {
    useAuthStore.setState({
      user: {
        id: 'user-1',
        email: 'user@example.com',
        roles: ['USER'],
        status: 'ACTIVE',
      },
      roles: ['USER'],
      isAuthenticated: true,
    });

    const { getByText } = renderScreen(<AnalyticsScreen />);

    expect(getByText('Access denied')).toBeTruthy();
    expect(getByText('This area requires an admin role.')).toBeTruthy();
    expect(mockedAnalyticsApi.getAnalyticsOverview).not.toHaveBeenCalled();
    expect(mockedAnalyticsApi.getParkingAnalytics).not.toHaveBeenCalled();
    expect(mockedAnalyticsApi.getAnalyticsMetrics).not.toHaveBeenCalled();
    expect(mockedAnalyticsApi.getDailyAnalytics).not.toHaveBeenCalled();
  });

  it('blocks moderators from analytics before fetching platform data', () => {
    useAuthStore.setState({
      user: {
        id: 'mod-1',
        email: 'mod@example.com',
        roles: ['MODERATOR'],
        status: 'ACTIVE',
      },
      roles: ['MODERATOR'],
      isAuthenticated: true,
    });

    const { getByText } = renderScreen(<AnalyticsScreen />);

    expect(getByText('Access denied')).toBeTruthy();
    expect(getByText('This area requires an admin role.')).toBeTruthy();
    expect(mockedAnalyticsApi.getAnalyticsOverview).not.toHaveBeenCalled();
  });

  it('lets admins load analytics without showing access denied', async () => {
    mockedAnalyticsApi.getAnalyticsOverview.mockResolvedValue({
      totalParkingCreated: 1,
      totalParkingVerified: 2,
      totalParkingClaimed: 3,
      totalParkingRejected: 0,
      totalPointsEarned: 4,
      totalLevelUps: 5,
      totalNotificationsCreated: 6,
    });
    mockedAnalyticsApi.getParkingAnalytics.mockResolvedValue([]);
    mockedAnalyticsApi.getAnalyticsMetrics.mockResolvedValue([]);
    mockedAnalyticsApi.getDailyAnalytics.mockResolvedValue([]);

    useAuthStore.setState({
      user: {
        id: 'admin-1',
        email: 'admin@example.com',
        roles: ['ADMIN'],
        status: 'ACTIVE',
      },
      roles: ['ADMIN'],
      isAuthenticated: true,
    });

    const { findByText, queryByText } = renderScreen(<AnalyticsScreen />);

    expect(queryByText('Access denied')).toBeNull();
    expect(await findByText('Overview — lifetime totals')).toBeTruthy();
    expect(mockedAnalyticsApi.getAnalyticsOverview).toHaveBeenCalled();
  });
});
