import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MapPage } from '@/pages/MapPage';

vi.mock('@/app/AppRuntimeContext', () => ({
  useParkioSdk: () => ({
    parkingApi: {
      getNearbySpots: vi.fn().mockResolvedValue([]),
      getNearbyMunicipalFacilities: vi.fn().mockResolvedValue([]),
      recommendParking: vi.fn().mockResolvedValue({
        destination: {
          label: 'Konak',
          latitude: 38.42,
          longitude: 27.13,
          source: 'GEOCODING',
        },
        generatedAt: new Date().toISOString(),
        partial: false,
        inventoryStatus: { community: 'AVAILABLE', municipal: 'AVAILABLE' },
        candidates: [],
        rankingStatus: 'APPLIED',
        rankingVersion: 'DETERMINISTIC_V1',
      }),
    },
    placesApi: {
      listSavedPlaces: vi.fn().mockResolvedValue([]),
      listFavouriteDestinations: vi.fn().mockResolvedValue([]),
      listRecentDestinations: vi.fn().mockResolvedValue([]),
      confirmRecentDestination: vi.fn().mockResolvedValue({
        id: 'r1',
        label: 'Konak',
        latitude: 38.42,
        longitude: 27.13,
        source: 'GEOCODING',
        firstUsedAt: new Date().toISOString(),
        lastUsedAt: new Date().toISOString(),
        useCount: 1,
      }),
    },
    geocodingApi: {
      searchPlaces: vi.fn().mockResolvedValue([]),
    },
  }),
}));

vi.mock('@/auth/store', () => {
  const store = { isAuthenticated: true };
  const useAuthStore = (sel: (s: typeof store) => unknown) => sel(store);
  useAuthStore.getState = () => store;
  return {
    useAuthStore,
    useAuthStoreApi: () => ({ getState: () => store }),
  };
});

vi.mock('@/components/parking/ParkHereStartControl', () => ({
  ParkHereStartControl: () => null,
}));

vi.mock('@/components/parking/ActiveParkingSessionCard', () => ({
  ActiveParkingSessionCard: () => null,
  ActiveParkingSessionErrorCard: () => null,
}));

vi.mock('@/components/map/NearbySpotsMap', () => ({
  NearbySpotsMap: () => <div data-testid="mock-map" />,
}));

vi.mock('@/data/hooks/useMeQueries', () => ({
  useMySmartReturnQuery: () => ({ data: null, isSuccess: false }),
  useMyVehicleQuery: () => ({ data: null }),
}));

vi.mock('@/data/hooks/useParkingSessionQueries', () => ({
  useActiveParkingSessionQuery: () => ({
    data: null,
    isPending: false,
    isError: false,
    refetch: vi.fn(),
  }),
  useParkingSessionLifecycleConfigQuery: () => ({ data: null }),
}));

vi.mock('@/lib/useMediaQuery', () => ({
  DESKTOP_QUERY: '(min-width: 768px)',
  useMediaQuery: () => true,
}));

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: 'tr' },
  }),
}));

function renderMap(flag: boolean) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/map']}>
        <MapPage
          municipalDiscoveryEnabled={false}
          smartParkingAssistantEnabled={flag}
        />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('MapPage smart parking assistant flag', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('hides assistant entry when flag is off', () => {
    renderMap(false);
    expect(screen.queryByTestId('assistant-entry')).not.toBeInTheDocument();
  });

  it('shows assistant entry when flag is on', () => {
    renderMap(true);
    expect(screen.getByTestId('assistant-entry')).toBeInTheDocument();
  });

  it('opens destination search from the entry control', () => {
    renderMap(true);
    fireEvent.click(screen.getByTestId('assistant-entry'));
    expect(screen.getByTestId('assistant-destination-search')).toBeInTheDocument();
  });
});
