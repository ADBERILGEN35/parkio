import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent } from '@testing-library/react-native';
import type { AppNotification } from '@parkio/types';
import { notificationsApi } from '@/services/api';
import { renderWithProviders } from '@/test/renderWithProviders';
import NotificationsScreen from '../notifications';

const mockPush = jest.fn();

jest.mock('expo-router', () => ({
  useRouter: () => ({ push: mockPush }),
}));

jest.mock('@/services/api', () => ({
  notificationsApi: {
    getMyNotifications: jest.fn(),
  },
}));

const mockedApi = notificationsApi as jest.Mocked<typeof notificationsApi>;

function makeNotification(overrides: Partial<AppNotification>): AppNotification {
  return {
    id: 'n1',
    type: 'SYSTEM',
    channel: 'IN_APP',
    title: 'Hello',
    body: 'Body',
    status: 'SENT',
    createdAt: new Date().toISOString(),
    readAt: null,
    ...overrides,
    metadata: overrides.metadata ?? {},
  };
}

function renderScreen() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: Infinity } },
  });
  return renderWithProviders(
    <QueryClientProvider client={queryClient}>
      <NotificationsScreen />
    </QueryClientProvider>,
  );
}

afterEach(() => jest.clearAllMocks());

// First render in the suite pays the module-transform cost, which can exceed
// the default 5s on the Windows-mounted filesystem.
jest.setTimeout(30_000);

describe('NotificationsScreen — Smart Return CTAs', () => {
  it('routes the prompt notification to the Smart Return Today flow', async () => {
    mockedApi.getMyNotifications.mockResolvedValue([
      makeNotification({
        id: 'p1',
        type: 'SMART_RETURN_PROMPT',
        title: 'Are you driving today?',
        body: 'Tell us when you’ll head home.',
      }),
    ]);

    const { findByTestId } = renderScreen();
    fireEvent.press(await findByTestId('notifications.smartReturn.prompt'));
    expect(mockPush).toHaveBeenCalledWith('/(main)/smart-return');
  });

  it('routes the availability notification to the map in Smart Return mode', async () => {
    mockedApi.getMyNotifications.mockResolvedValue([
      makeNotification({
        id: 'a1',
        type: 'SMART_RETURN_AVAILABLE',
        title: 'Parking near home',
        body: 'A spot just opened near your home area.',
      }),
    ]);

    const { findByTestId } = renderScreen();
    fireEvent.press(await findByTestId('notifications.smartReturn.available'));
    expect(mockPush).toHaveBeenCalledWith({ pathname: '/(main)/map', params: { smartReturn: '1' } });
  });

  it('renders no CTA for other notification types', async () => {
    mockedApi.getMyNotifications.mockResolvedValue([
      makeNotification({ id: 's1', type: 'SYSTEM', title: 'Maintenance', body: 'Tonight.' }),
    ]);

    const { findByText, queryByTestId } = renderScreen();
    expect(await findByText('Maintenance')).toBeTruthy();
    expect(queryByTestId('notifications.smartReturn.prompt')).toBeNull();
    expect(queryByTestId('notifications.smartReturn.available')).toBeNull();
  });
});
