import { act, fireEvent, screen, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactElement, ReactNode } from 'react';
import { render } from '@testing-library/react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { NetworkError } from '@parkio/api-client';
import { LocaleProvider } from '@/i18n/LocaleProvider';
import { ThemeProvider } from '@/theme/ThemeProvider';
import { parkingKeys } from '@/data/keys';
import { useAuthStore } from '@/state/authStore';
import { ActiveParkingSessionBanner } from '../ActiveParkingSessionBanner';
import * as api from '@/services/api';

jest.mock('@/services/api', () => ({
  parkingApi: {
    getActiveParkingSession: jest.fn(),
    completeParkingSession: jest.fn(),
    cancelParkingSession: jest.fn(),
  },
}));

const activeParkingSessionFixture = {
  id: 'd431ad5a-f8ce-4be2-b4dc-248b47990b39',
  status: 'ACTIVE' as const,
  parkingSource: 'MANUAL' as const,
  startedAt: '2026-07-21T09:00:00.000Z',
  endedAt: null,
  latitude: 41.0082,
  longitude: 28.9784,
  estimatedFee: '125.50',
};

function renderBanner(ui: ReactElement = <ActiveParkingSessionBanner />) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  });
  function Wrapper({ children }: { children: ReactNode }) {
    return (
      <SafeAreaProvider
        initialMetrics={{
          frame: { x: 0, y: 0, width: 393, height: 852 },
          insets: { top: 47, left: 0, right: 0, bottom: 34 },
        }}
      >
        <ThemeProvider>
          <LocaleProvider>
            <QueryClientProvider client={client}>{children}</QueryClientProvider>
          </LocaleProvider>
        </ThemeProvider>
      </SafeAreaProvider>
    );
  }
  return { ...render(ui, { wrapper: Wrapper }), client };
}

describe('ActiveParkingSessionBanner S1-P0-04 terminal UI', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    jest.useFakeTimers();
    jest.setSystemTime(new Date('2026-07-21T09:05:00.000Z'));
    useAuthStore.setState({
      status: 'authenticated',
      user: { id: 'user-1', email: 'a@b.c', displayName: 'A', roles: ['USER'] } as never,
      sessionEpoch: 1,
    });
  });

  afterEach(() => {
    jest.useRealTimers();
  });

  it('shows elapsed derived from startedAt and updates after time jump', async () => {
    (api.parkingApi.getActiveParkingSession as jest.Mock).mockResolvedValue(
      activeParkingSessionFixture,
    );
    renderBanner();

    await waitFor(() => {
      expect(screen.getByTestId('active-parking-elapsed')).toBeTruthy();
      expect(screen.getByText('05:00')).toBeTruthy();
    });

    await act(async () => {
      jest.setSystemTime(new Date('2026-07-21T09:06:30.000Z'));
      jest.advanceTimersByTime(1000);
    });

    await waitFor(() => {
      expect(screen.getByText('06:31')).toBeTruthy();
    });
  });

  it('Ayrıldım completes and removes active banner', async () => {
    (api.parkingApi.getActiveParkingSession as jest.Mock).mockResolvedValue(
      activeParkingSessionFixture,
    );
    (api.parkingApi.completeParkingSession as jest.Mock).mockResolvedValue({
      ...activeParkingSessionFixture,
      status: 'COMPLETED',
      endedAt: '2026-07-21T09:10:00.000Z',
    });
    const { client } = renderBanner();

    await waitFor(() => expect(screen.getByText('Ayrıldım')).toBeTruthy());
    fireEvent.press(screen.getByText('Ayrıldım'));

    await waitFor(() => {
      expect(api.parkingApi.completeParkingSession).toHaveBeenCalledTimes(1);
      expect(screen.queryByTestId('active-parking-session')).toBeNull();
    });
    expect(client.getQueryData(parkingKeys.activeSession())).toBeNull();
    const [id, key] = (api.parkingApi.completeParkingSession as jest.Mock).mock.calls[0];
    expect(id).toBe(activeParkingSessionFixture.id);
    expect(typeof key).toBe('string');
    expect(screen.queryByText(String(key))).toBeNull();
  });

  it('cancel uses ConfirmModal then cancelParkingSession', async () => {
    (api.parkingApi.getActiveParkingSession as jest.Mock).mockResolvedValue(
      activeParkingSessionFixture,
    );
    (api.parkingApi.cancelParkingSession as jest.Mock).mockResolvedValue({
      ...activeParkingSessionFixture,
      status: 'CANCELLED',
      endedAt: '2026-07-21T09:10:00.000Z',
    });
    renderBanner();

    await waitFor(() => expect(screen.getByText('İptal')).toBeTruthy());
    fireEvent.press(screen.getByText('İptal'));

    await waitFor(() => {
      expect(screen.getByText('Park oturumunu iptal et?')).toBeTruthy();
    });
    fireEvent.press(screen.getByText('İptal et'));

    await waitFor(() => {
      expect(api.parkingApi.cancelParkingSession).toHaveBeenCalledTimes(1);
      expect(api.parkingApi.completeParkingSession).not.toHaveBeenCalled();
      expect(screen.queryByTestId('active-parking-session')).toBeNull();
    });
  });

  it('ambiguous complete shows retry without raw errors and blocks cancel', async () => {
    (api.parkingApi.getActiveParkingSession as jest.Mock).mockResolvedValue(
      activeParkingSessionFixture,
    );
    (api.parkingApi.completeParkingSession as jest.Mock).mockRejectedValueOnce(
      new NetworkError('ECONNRESET secret'),
    );
    renderBanner();

    await waitFor(() => expect(screen.getByText('Ayrıldım')).toBeTruthy());
    fireEvent.press(screen.getByText('Ayrıldım'));

    await waitFor(() => {
      expect(screen.getByText(/belirsiz/i)).toBeTruthy();
      expect(screen.getByText('Tekrar dene')).toBeTruthy();
    });
    expect(screen.queryByText(/ECONNRESET/)).toBeNull();
    expect(screen.queryByText(/ACTIVE_PARKING/)).toBeNull();

    // Cancel remains disabled while complete attempt is outstanding.
    expect(screen.getByLabelText('İptal')).toBeDisabled();
  });
});