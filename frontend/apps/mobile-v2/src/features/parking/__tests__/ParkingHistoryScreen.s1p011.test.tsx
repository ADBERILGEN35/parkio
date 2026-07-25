import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import ParkingHistoryScreen from '../../../../app/(main)/profile/parking-history';
import { parkingKeys } from '@/data/keys';
import { LocaleProvider } from '@/i18n/LocaleProvider';
import { ToastProvider } from '@/providers/ToastProvider';
import { useAuthStore } from '@/state/authStore';
import { ThemeProvider } from '@/theme/ThemeProvider';
import * as api from '@/services/api';

const mockCompleted = {
  id: 'd431ad5a-f8ce-4be2-b4dc-248b47990b39',
  status: 'COMPLETED' as const,
  parkingSource: 'MANUAL' as const,
  startedAt: '2026-07-21T09:00:00Z',
  endedAt: '2026-07-21T10:00:00Z',
  latitude: 41.0082,
  longitude: 28.9784,
  estimatedFee: null,
  lastConfirmedAt: '2026-07-25T10:00:00.000Z',
  completionType: null,
};

const mockCancelled = {
  ...mockCompleted,
  id: 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
  status: 'CANCELLED' as const,
};

jest.mock('expo-router', () => ({
  useRouter: () => ({ back: jest.fn(), canGoBack: () => true, replace: jest.fn() }),
}));

jest.mock('@/services/api', () => ({
  parkingApi: {
    getParkingSessionHistory: jest.fn(async () => ({
      items: [mockCompleted, mockCancelled],
      nextCursor: null,
    })),
    deleteParkingSession: jest.fn(async () => undefined),
    deleteParkingSessionHistory: jest.fn(async () => undefined),
    getActiveParkingSession: jest.fn(async () => null),
  },
}));

jest.mock('@/hooks/useOnlineStatus', () => ({
  useOnlineStatus: () => true,
}));

function renderScreen(client: QueryClient) {
  const wrapper = ({ children }: { children: ReactNode }) => (
    <SafeAreaProvider
      initialMetrics={{
        frame: { x: 0, y: 0, width: 390, height: 844 },
        insets: { top: 0, left: 0, right: 0, bottom: 0 },
      }}
    >
      <ThemeProvider>
        <LocaleProvider>
          <ToastProvider>
            <QueryClientProvider client={client}>{children}</QueryClientProvider>
          </ToastProvider>
        </LocaleProvider>
      </ThemeProvider>
    </SafeAreaProvider>
  );
  return render(<ParkingHistoryScreen />, { wrapper });
}

describe('S1-P0-11 ParkingHistoryScreen', () => {
  let client: QueryClient;

  beforeEach(() => {
    jest.clearAllMocks();
    client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    useAuthStore.setState({
      status: 'authenticated',
      user: { id: 'user-a', email: 'a@parkio.dev', displayName: 'A', roles: ['USER'] } as never,
      sessionEpoch: 1,
    });
    (api.parkingApi.getParkingSessionHistory as jest.Mock).mockResolvedValue({
      items: [mockCompleted, mockCancelled],
      nextCursor: null,
    });
  });

  it('renders populated history without coordinates or session UUID', async () => {
    renderScreen(client);
    await waitFor(() => expect(screen.getByTestId('parking-history-list')).toBeTruthy());
    expect(screen.getByText('Tamamlandı')).toBeTruthy();
    expect(screen.getByText('İptal edildi')).toBeTruthy();
    expect(screen.queryByText(mockCompleted.id)).toBeNull();
    expect(screen.queryByText(String(mockCompleted.latitude))).toBeNull();
    expect(screen.queryByText(String(mockCompleted.longitude))).toBeNull();
  });

  it('shows empty state when history is empty', async () => {
    (api.parkingApi.getParkingSessionHistory as jest.Mock).mockResolvedValue({
      items: [],
      nextCursor: null,
    });
    renderScreen(client);
    await waitFor(() => expect(screen.getByText('Geçmiş park oturumu yok')).toBeTruthy());
  });

  it('opens individual delete confirmation and calls delete once on confirm', async () => {
    renderScreen(client);
    await waitFor(() => expect(screen.getByTestId('parking-history-list')).toBeTruthy());

    fireEvent.press(screen.getByLabelText('Tamamlandı park kaydını sil'));
    await waitFor(() => expect(screen.getByText('Bu park kaydını sil?')).toBeTruthy());
    fireEvent.press(screen.getByText('Sil'));

    await waitFor(() => expect(api.parkingApi.deleteParkingSession).toHaveBeenCalledTimes(1));
    expect(api.parkingApi.deleteParkingSession).toHaveBeenCalledWith(mockCompleted.id);
  });

  it('shows delete-all confirmation preserving ACTIVE wording and keeps ACTIVE cache', async () => {
    client.setQueryData(parkingKeys.activeSession(), {
      ...mockCompleted,
      status: 'ACTIVE',
      endedAt: null,
    });
    renderScreen(client);
    await waitFor(() => expect(screen.getByText('Tümünü sil')).toBeTruthy());

    fireEvent.press(screen.getByText('Tümünü sil'));
    await waitFor(() => expect(screen.getByText('Tüm park geçmişini sil?')).toBeTruthy());
    expect(screen.getByText(/aktif park oturumun korunur/i)).toBeTruthy();

    const deleteAllButtons = screen.getAllByText('Tümünü sil');
    fireEvent.press(deleteAllButtons[deleteAllButtons.length - 1]!);
    await waitFor(() => expect(api.parkingApi.deleteParkingSessionHistory).toHaveBeenCalledTimes(1));
    expect(client.getQueryData(parkingKeys.activeSession())).toMatchObject({ status: 'ACTIVE' });
  });
});
