import { fireEvent, screen, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactElement, ReactNode } from 'react';
import { render } from '@testing-library/react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { Share } from 'react-native';
import * as Linking from 'expo-linking';
import { LocaleProvider } from '@/i18n/LocaleProvider';
import { ThemeProvider } from '@/theme/ThemeProvider';
import { ToastProvider } from '@/providers/ToastProvider';
import { useAuthStore } from '@/state/authStore';
import { ActiveParkingSessionBanner } from '../ActiveParkingSessionBanner';
import * as api from '@/services/api';
import * as productAnalytics from '@/services/productAnalytics';

jest.mock('@/services/api', () => ({
  parkingApi: {
    getActiveParkingSession: jest.fn(),
    completeParkingSession: jest.fn(),
    cancelParkingSession: jest.fn(),
  },
}));

jest.mock('expo-linking', () => ({
  openURL: jest.fn(),
}));

jest.mock('@/services/productAnalytics', () => {
  const actual = jest.requireActual('@/services/productAnalytics');
  return {
    ...actual,
    trackProductEvent: jest.fn(),
  };
});

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
            <ToastProvider>
              <QueryClientProvider client={client}>{children}</QueryClientProvider>
            </ToastProvider>
          </LocaleProvider>
        </ThemeProvider>
      </SafeAreaProvider>
    );
  }
  return { ...render(ui, { wrapper: Wrapper }), client };
}

describe('ActiveParkingSessionBanner S1-P0-10 location actions', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    jest.useFakeTimers();
    jest.setSystemTime(new Date('2026-07-21T09:05:00.000Z'));
    useAuthStore.setState({
      status: 'authenticated',
      user: { id: 'user-1', email: 'a@b.c', displayName: 'A', roles: ['USER'] } as never,
      sessionEpoch: 1,
    });
    (Linking.openURL as jest.Mock).mockResolvedValue(undefined);
    jest.spyOn(Share, 'share').mockResolvedValue({ action: Share.sharedAction } as never);
  });

  afterEach(() => {
    jest.useRealTimers();
  });

  it('renders navigate and share controls with a11y labels; keeps complete/cancel', async () => {
    (api.parkingApi.getActiveParkingSession as jest.Mock).mockResolvedValue(
      activeParkingSessionFixture,
    );
    renderBanner();

    await waitFor(() => {
      expect(screen.getByTestId('active-parking-location-actions')).toBeTruthy();
      expect(screen.getByLabelText('Arabamı bul — haritada yol tarifi aç')).toBeTruthy();
      expect(screen.getByLabelText('Park konumunu paylaş')).toBeTruthy();
      expect(screen.getByText('Ayrıldım')).toBeTruthy();
      expect(screen.getByText('İptal')).toBeTruthy();
      expect(screen.getByTestId('active-parking-elapsed')).toBeTruthy();
    });

    // Coordinates must not appear in the UI tree text.
    expect(screen.queryByText(/41\.0082/)).toBeNull();
    expect(screen.queryByText(/28\.9784/)).toBeNull();
  });

  it('navigate press opens maps and tracks interaction event only', async () => {
    (api.parkingApi.getActiveParkingSession as jest.Mock).mockResolvedValue(
      activeParkingSessionFixture,
    );
    renderBanner();

    await waitFor(() => screen.getByLabelText('Arabamı bul — haritada yol tarifi aç'));
    fireEvent.press(screen.getByLabelText('Arabamı bul — haritada yol tarifi aç'));

    await waitFor(() => {
      expect(Linking.openURL).toHaveBeenCalled();
      expect(productAnalytics.trackProductEvent).toHaveBeenCalledWith(
        'return_to_car_clicked',
        expect.objectContaining({ platform: expect.any(String) }),
      );
    });

    const allArgs = (productAnalytics.trackProductEvent as jest.Mock).mock.calls.flat();
    expect(allArgs).not.toContain('parking_session_started');
    expect(JSON.stringify(allArgs)).not.toMatch(/41\.0082|28\.9784/);
  });

  it('share press invokes native Share without lifecycle event names', async () => {
    (api.parkingApi.getActiveParkingSession as jest.Mock).mockResolvedValue(
      activeParkingSessionFixture,
    );
    renderBanner();

    await waitFor(() => screen.getByLabelText('Park konumunu paylaş'));
    fireEvent.press(screen.getByLabelText('Park konumunu paylaş'));

    await waitFor(() => {
      expect(Share.share).toHaveBeenCalled();
      expect(productAnalytics.trackProductEvent).toHaveBeenCalledWith(
        'parking_location_shared',
        expect.any(Object),
      );
    });
  });
});