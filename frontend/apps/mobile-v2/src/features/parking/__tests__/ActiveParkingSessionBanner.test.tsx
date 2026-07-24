import { fireEvent, screen, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactElement, ReactNode } from 'react';
import { render } from '@testing-library/react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { NetworkError } from '@parkio/api-client';
const activeParkingSessionFixture = {
  id: 'd431ad5a-f8ce-4be2-b4dc-248b47990b39',
  status: 'ACTIVE' as const,
  parkingSource: 'MANUAL' as const,
  startedAt: '2026-07-21T09:00:00Z',
  endedAt: null,
  latitude: 41.0082,
  longitude: 28.9784,
  estimatedFee: '125.50',
};

import { LocaleProvider } from '@/i18n/LocaleProvider';
import { ThemeProvider } from '@/theme/ThemeProvider';
import { parkingKeys } from '@/data/keys';
import { ActiveParkingSessionBanner } from '../ActiveParkingSessionBanner';
import * as api from '@/services/api';

jest.mock('@/services/api', () => ({
  parkingApi: {
    getActiveParkingSession: jest.fn(),
    completeParkingSession: jest.fn(),
    cancelParkingSession: jest.fn(),
  },
}));

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

describe('ActiveParkingSessionBanner', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('shows loading then hides for empty/null (204)', async () => {
    let resolve!: (value: null) => void;
    (api.parkingApi.getActiveParkingSession as jest.Mock).mockReturnValueOnce(
      new Promise<null>((r) => {
        resolve = r;
      }),
    );

    renderBanner();
    expect(screen.getByLabelText('Aktif park oturumu yükleniyor…')).toBeTruthy();

    resolve(null);
    await waitFor(() => {
      expect(screen.queryByLabelText('Aktif park oturumu yükleniyor…')).toBeNull();
      expect(screen.queryByTestId('active-parking-session')).toBeNull();
      expect(screen.queryByText('Aktif park')).toBeNull();
    });
  });

  it('renders active state without coordinates, UUID, or raw enums', async () => {
    (api.parkingApi.getActiveParkingSession as jest.Mock).mockResolvedValueOnce(
      activeParkingSessionFixture,
    );

    renderBanner();

    await waitFor(() => {
      expect(screen.getByTestId('active-parking-session')).toBeTruthy();
      expect(screen.getByText('Aktif park')).toBeTruthy();
      expect(screen.getByText('Aktif')).toBeTruthy();
      expect(screen.getByText('Ayrıldım')).toBeTruthy();
      expect(screen.getByText('İptal')).toBeTruthy();
      expect(screen.getByTestId('active-parking-elapsed')).toBeTruthy();
    });

    expect(screen.queryByText(activeParkingSessionFixture.id)).toBeNull();
    expect(screen.queryByText('MANUAL')).toBeNull();
    expect(screen.queryByText(String(activeParkingSessionFixture.latitude))).toBeNull();
    expect(screen.queryByText(String(activeParkingSessionFixture.longitude))).toBeNull();
    expect(screen.queryByText(/ACTIVE_PARKING/)).toBeNull();
    expect(screen.queryByText(activeParkingSessionFixture.startedAt)).toBeNull();
  });

  it('shows retryable error and refetches without raw API text', async () => {
    (api.parkingApi.getActiveParkingSession as jest.Mock)
      .mockRejectedValueOnce(new NetworkError('ECONNRESET secret-stack'))
      .mockResolvedValueOnce(activeParkingSessionFixture);

    renderBanner();

    await waitFor(() => {
      expect(screen.getByText('Aktif park oturumu alınamadı')).toBeTruthy();
      expect(screen.getByText('Sunucuya ulaşılamadı. Bağlantını kontrol et.')).toBeTruthy();
    });
    expect(screen.queryByText(/ECONNRESET/)).toBeNull();
    expect(screen.queryByText(/secret-stack/)).toBeNull();

    fireEvent.press(screen.getByText('Tekrar dene'));

    await waitFor(() => {
      expect(screen.getByTestId('active-parking-session')).toBeTruthy();
    });
    expect(api.parkingApi.getActiveParkingSession).toHaveBeenCalledTimes(2);
  });

  it('keeps neighboring map chrome concepts intact when empty', async () => {
    (api.parkingApi.getActiveParkingSession as jest.Mock).mockResolvedValueOnce(null);
    const { client } = renderBanner();
    await waitFor(() => {
      expect(screen.queryByTestId('active-parking-session')).toBeNull();
    });
    // Empty null must not poison the sessions key with a fabricated object.
    expect(client.getQueryData(parkingKeys.activeSession())).toBeNull();
  });
});
