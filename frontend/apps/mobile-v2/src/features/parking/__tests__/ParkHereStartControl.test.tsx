import { fireEvent, screen, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactElement, ReactNode } from 'react';
import { createElement } from 'react';
import { render } from '@testing-library/react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { Linking } from 'react-native';
import { NetworkError } from '@parkio/api-client';
import { LocaleProvider } from '@/i18n/LocaleProvider';
import { ThemeProvider } from '@/theme/ThemeProvider';
import { parkingKeys } from '@/data/keys';
import type { LocationState } from '@/features/map/hooks';
import { ParkHereStartControl } from '../ParkHereStartControl';
import { useAuthStore } from '@/state/authStore';
import * as api from '@/services/api';

jest.mock('@/services/api', () => ({
  parkingApi: {
    startParkingSession: jest.fn(),
    getActiveParkingSession: jest.fn(async () => null),
  },
}));

jest.mock('@/hooks/useOnlineStatus', () => ({
  useOnlineStatus: jest.fn(() => true),
}));

const sessionFixture = {
  id: 'd431ad5a-f8ce-4be2-b4dc-248b47990b39',
  status: 'ACTIVE' as const,
  parkingSource: 'MANUAL' as const,
  startedAt: '2026-07-21T09:00:00Z',
  endedAt: null,
  latitude: 41.0082,
  longitude: 28.9784,
  estimatedFee: null,
  lastConfirmedAt: '2026-07-25T10:00:00.000Z',
  completionType: null,
};

function createLocation(overrides: Partial<LocationState> = {}): LocationState {
  return {
    status: 'granted',
    canAskAgain: true,
    getCanAskAgain: () => true,
    position: { lat: 41.0082, lng: 28.9784 },
    accuracy: 10,
    request: jest.fn(async () => ({ lat: 41.0082, lng: 28.9784 })),
    refresh: jest.fn(async () => ({ lat: 41.0082, lng: 28.9784 })),
    ...overrides,
  };
}

function renderControl(location: LocationState = createLocation(), client?: QueryClient) {
  const queryClient =
    client ??
    new QueryClient({
      defaultOptions: { queries: { retry: false, gcTime: 0 } },
    });
  queryClient.setQueryData(parkingKeys.activeSession(), null);

  function Wrapper({ children }: { children: ReactNode }) {
    return createElement(
      SafeAreaProvider,
      {
        initialMetrics: {
          frame: { x: 0, y: 0, width: 393, height: 852 },
          insets: { top: 47, left: 0, right: 0, bottom: 34 },
        },
      },
      createElement(
        ThemeProvider,
        null,
        createElement(
          LocaleProvider,
          null,
          createElement(QueryClientProvider, { client: queryClient }, children),
        ),
      ),
    );
  }

  const ui: ReactElement = createElement(ParkHereStartControl, { location });
  return { ...render(ui, { wrapper: Wrapper }), client: queryClient };
}

describe('ParkHereStartControl', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    useAuthStore.setState({
      status: 'authenticated',
      user: { id: 'user-1', email: 'a@b.c', displayName: 'A', roles: ['USER'] } as never,
      sessionEpoch: 1,
    });
    (api.parkingApi.getActiveParkingSession as jest.Mock).mockResolvedValue(null);
  });

  it('renders start CTA when authenticated and no active session', async () => {
    renderControl();
    await waitFor(() => {
      expect(screen.getByTestId('park-here-start')).toBeTruthy();
      expect(screen.getByRole('button', { name: 'Park ettim' })).toBeTruthy();
    });
  });

  it('hides start CTA when an ACTIVE session exists and keeps banner data intact', async () => {
    (api.parkingApi.getActiveParkingSession as jest.Mock).mockResolvedValue(sessionFixture);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
    client.setQueryData(parkingKeys.activeSession(), sessionFixture);
    renderControl(createLocation(), client);

    await waitFor(() => {
      expect(screen.queryByTestId('park-here-start')).toBeNull();
    });
    expect(client.getQueryData(parkingKeys.activeSession())).toEqual(sessionFixture);
  });

  it('successful start reveals session without coordinates or UUID', async () => {
    (api.parkingApi.startParkingSession as jest.Mock).mockResolvedValueOnce(sessionFixture);
    const { client } = renderControl();

    await waitFor(() => expect(screen.getByTestId('park-here-start')).toBeTruthy());
    fireEvent.press(screen.getByRole('button', { name: 'Park ettim' }));

    await waitFor(() => {
      expect(client.getQueryData(parkingKeys.activeSession())).toEqual(sessionFixture);
      expect(screen.queryByTestId('park-here-start')).toBeNull();
    });
    expect(screen.queryByText(sessionFixture.id)).toBeNull();
    expect(screen.queryByText(String(sessionFixture.latitude))).toBeNull();
    expect(screen.queryByText('MANUAL')).toBeNull();
  });

  it('shows retryable ambiguous state without raw network text', async () => {
    (api.parkingApi.startParkingSession as jest.Mock).mockRejectedValueOnce(
      new NetworkError('ECONNRESET secret-stack'),
    );
    (api.parkingApi.getActiveParkingSession as jest.Mock).mockResolvedValue(null);
    renderControl();

    await waitFor(() => expect(screen.getByTestId('park-here-start')).toBeTruthy());
    fireEvent.press(screen.getByRole('button', { name: 'Park ettim' }));

    await waitFor(() => {
      expect(screen.getByTestId('park-here-error')).toBeTruthy();
      expect(screen.getByText('Bağlantı kesildi')).toBeTruthy();
    });
    expect(screen.queryByText(/ECONNRESET/)).toBeNull();
    expect(screen.queryByText(/secret-stack/)).toBeNull();
    expect(screen.queryByText(/ACTIVE_PARKING/)).toBeNull();
  });

  it('denied-cannot-ask-again offers open settings', async () => {
    const openSettings = jest.spyOn(Linking, 'openSettings').mockResolvedValue();
    const location = createLocation({
      status: 'denied',
      canAskAgain: false,
      getCanAskAgain: () => false,
      position: null,
      request: jest.fn(async () => null),
    });
    renderControl(location);

    await waitFor(() => expect(screen.getByTestId('park-here-start')).toBeTruthy());
    fireEvent.press(screen.getByRole('button', { name: 'Park ettim' }));

    await waitFor(() => {
      expect(screen.getByText('Ayarları aç')).toBeTruthy();
    });
    fireEvent.press(screen.getByText('Ayarları aç'));
    expect(openSettings).toHaveBeenCalled();
    openSettings.mockRestore();
  });
});