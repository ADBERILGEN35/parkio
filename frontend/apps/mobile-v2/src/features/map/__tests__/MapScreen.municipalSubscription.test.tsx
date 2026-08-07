import React from 'react';
import { View } from 'react-native';
import { act, fireEvent, waitFor } from '@testing-library/react-native';
import { renderWithProviders } from '@/test/renderWithProviders';

const mockMunicipalDiscovery = jest.fn(() => true);
const mockNearbyMunicipal = jest.fn();

jest.mock('@/config/env', () => ({
  appConfig: {
    appEnv: 'development',
    isProductionLike: false,
    apiBaseUrl: 'http://localhost:8080/api/v1',
    get features() {
      return {
        smartReturn: true,
        municipalDiscovery: mockMunicipalDiscovery(),
        smartParkingAssistant: false,
      };
    },
  },
}));

jest.mock('expo-router', () => ({
  useRouter: () => ({ push: jest.fn(), replace: jest.fn(), back: jest.fn() }),
}));

jest.mock('expo-linking', () => ({
  openSettings: jest.fn(async () => undefined),
  canOpenURL: jest.fn(async () => true),
  openURL: jest.fn(async () => undefined),
}));

jest.mock('@gorhom/bottom-sheet', () => {
  const ReactLocal = require('react');
  const ReactNative = require('react-native');
  return {
    __esModule: true,
    default: ReactLocal.forwardRef(({ children }: { children: React.ReactNode }, _ref: unknown) => (
      <ReactNative.View testID="bottom-sheet">{children}</ReactNative.View>
    )),
    BottomSheetView: ({ children }: { children: React.ReactNode }) => (
      <ReactNative.View>{children}</ReactNative.View>
    ),
    BottomSheetScrollView: ({ children }: { children: React.ReactNode }) => (
      <ReactNative.View>{children}</ReactNative.View>
    ),
    BottomSheetFlatList: ({
      data,
      renderItem,
    }: {
      data: unknown[];
      renderItem: (info: { item: unknown; index: number }) => React.ReactNode;
    }) => (
      <ReactNative.View>
        {(data ?? []).map((item, index) => (
          <ReactNative.View key={String(index)}>{renderItem({ item, index })}</ReactNative.View>
        ))}
      </ReactNative.View>
    ),
  };
});

jest.mock('@/features/map/MapSurface', () => {
  const ReactLocal = require('react');
  const ReactNative = require('react-native');
  return {
    MapSurface: ReactLocal.forwardRef((_props: unknown, ref: React.Ref<unknown>) => {
      ReactLocal.useImperativeHandle(ref, () => ({
        flyTo: jest.fn(),
        setUserLocation: jest.fn(),
        setSpots: jest.fn(),
        setSelected: jest.fn(),
        setMunicipalFacilities: jest.fn(),
        setSelectedMunicipal: jest.fn(),
        setDestinationMarker: jest.fn(),
        setRecommendedHighlights: jest.fn(),
      }));
      return <ReactNative.View testID="map-surface" />;
    }),
  };
});

jest.mock('@/features/map/hooks', () => ({
  useLocation: () => ({
    status: 'granted',
    canAskAgain: true,
    getCanAskAgain: () => true,
    position: { lat: 38.4237, lng: 27.1428 },
    accuracy: 12,
    request: jest.fn(async () => ({ lat: 38.4237, lng: 27.1428 })),
    refresh: jest.fn(async () => ({ lat: 38.4237, lng: 27.1428 })),
  }),
  useAccessPolicy: () => ({
    data: {
      currentLevel: 1,
      searchRadiusMeters: 800,
      resultLimit: 50,
      dailyViewLimit: 40,
    },
    isError: false,
  }),
  useNearbySpots: () => ({
    data: [],
    isSuccess: true,
    isError: false,
    error: null,
    dataUpdatedAt: Date.now(),
  }),
  useNearbyMunicipalFacilities: (...args: unknown[]) => mockNearbyMunicipal(...args),
  usePlaceSearch: () => ({ data: [], isFetching: false }),
  useRecentSearches: () => ({ recent: [], push: jest.fn() }),
}));

jest.mock('@/features/smart-return/useSmartReturn', () => ({
  useSmartReturn: () => ({ data: null }),
  useSmartReturnMutations: () => ({
    leftByCar: { isPending: false, mutate: jest.fn() },
    notByCar: { isPending: false, mutate: jest.fn() },
  }),
  todayAt: jest.fn(),
  todayKey: () => '2026-08-06',
}));

jest.mock('@/features/smart-return/MorningPromptModal', () => ({
  MorningPromptModal: () => null,
}));

jest.mock('@/features/parking/ActiveParkingSessionBanner', () => ({
  ActiveParkingSessionBanner: () => null,
}));

jest.mock('@/features/parking/useActiveParkingSession', () => ({
  useActiveParkingSession: () => ({
    data: null,
    isPending: false,
    isError: false,
    isSuccess: true,
  }),
}));

jest.mock('@/features/parking/useParkingLocationActions', () => ({
  useParkingLocationActions: () => ({
    phase: 'idle',
    busy: false,
    destinationValid: false,
    navigateDisabled: true,
    shareDisabled: true,
    navigate: jest.fn(async () => undefined),
    share: jest.fn(async () => undefined),
  }),
}));

jest.mock('@/features/parking/ParkHereStartControl', () => ({
  ParkHereStartControl: () => null,
}));

jest.mock('@/features/share/shareSheetStore', () => ({
  useShareSheetStore: (selector: (s: { open: () => void }) => unknown) =>
    selector({ open: jest.fn() }),
}));

jest.mock('@/services/jsonStore', () => ({
  readJson: jest.fn(async () => null),
  writeJson: jest.fn(async () => undefined),
}));

import { DEFAULT_MUNICIPAL_MAP_FILTERS } from '@/features/municipal/municipalFilterModel';
import { useMunicipalFilterStore } from '@/features/municipal/municipalFilterStore';
import { useAuthStore } from '@/state/authStore';
import MapScreen from '../../../../app/(main)/(tabs)/map';

const RENDER_BUDGET = 40;

describe('MapScreen municipal filter subscription (runtime regression)', () => {
  let consoleErrorSpy: jest.SpyInstance;
  let mapRenderCount = 0;

  beforeEach(() => {
    jest.clearAllMocks();
    mapRenderCount = 0;
    mockMunicipalDiscovery.mockReturnValue(true);
    mockNearbyMunicipal.mockReturnValue({
      data: [],
      isFetching: false,
      isSuccess: true,
      isError: false,
      error: null,
    });
    useMunicipalFilterStore.setState({
      ...DEFAULT_MUNICIPAL_MAP_FILTERS,
      hydrated: true,
    });
    useAuthStore.setState({
      status: 'authenticated',
      user: {
        id: 'user-1',
        email: 'demo@parkio.dev',
        displayName: 'Demo',
        roles: ['USER'],
      } as never,
      sessionEpoch: 1,
    });

    consoleErrorSpy = jest.spyOn(console, 'error').mockImplementation((...args: unknown[]) => {
      const message = args.map(String).join(' ');
      if (
        message.includes('getSnapshot should be cached') ||
        message.includes('Maximum update depth exceeded')
      ) {
        throw new Error(message);
      }
    });
  });

  afterEach(() => {
    consoleErrorSpy.mockRestore();
  });

  function MountProbe({ children }: { children: React.ReactNode }) {
    mapRenderCount += 1;
    if (mapRenderCount > RENDER_BUDGET) {
      throw new Error(`Maximum update depth exceeded (render budget ${RENDER_BUDGET})`);
    }
    return <View testID="mount-probe">{children}</View>;
  }

  it('mounts with municipal flag ON without getSnapshot / update-depth loops', async () => {
    mockMunicipalDiscovery.mockReturnValue(true);

    const { getByTestId, getByLabelText, queryByLabelText } = renderWithProviders(
      <MountProbe>
        <MapScreen />
      </MountProbe>,
    );

    await waitFor(() => {
      expect(getByTestId('map-surface')).toBeTruthy();
    });
    expect(getByLabelText('Belediye filtreleri')).toBeTruthy();
    expect(mapRenderCount).toBeLessThanOrEqual(RENDER_BUDGET);
    expect(mockNearbyMunicipal).toHaveBeenCalled();
    // Center is set from location — municipal nearby should be enabled with a center.
    expect(mockNearbyMunicipal.mock.calls.some((call) => call[0] != null)).toBe(true);
    expect(queryByLabelText('Belediye filtreleri')).toBeTruthy();

    // Idle: allow microtasks; render count must remain bounded.
    await act(async () => {
      await Promise.resolve();
      await Promise.resolve();
    });
    expect(mapRenderCount).toBeLessThanOrEqual(RENDER_BUDGET);
  });

  it('mounts with municipal flag OFF without crashing and hides municipal chrome', async () => {
    mockMunicipalDiscovery.mockReturnValue(false);

    const { getByTestId, queryByLabelText } = renderWithProviders(
      <MountProbe>
        <MapScreen />
      </MountProbe>,
    );

    await waitFor(() => {
      expect(getByTestId('map-surface')).toBeTruthy();
    });
    expect(queryByLabelText('Belediye filtreleri')).toBeNull();
    expect(mapRenderCount).toBeLessThanOrEqual(RENDER_BUDGET);

    // Flag OFF: hook still runs, but nearby must be called with null center (layer gated).
    const centers = mockNearbyMunicipal.mock.calls.map((call) => call[0]);
    expect(centers.every((center) => center == null)).toBe(true);
  });

  it('opens the filter sheet and applies a source change without looping', async () => {
    mockMunicipalDiscovery.mockReturnValue(true);

    const { getByLabelText, getByText, getAllByText } = renderWithProviders(
      <MountProbe>
        <MapScreen />
      </MountProbe>,
    );

    fireEvent.press(getByLabelText('Belediye filtreleri'));
    await waitFor(() => {
      expect(getByText('Belediye otoparkları')).toBeTruthy();
    });

    fireEvent.press(getByText(/İzmir Büyükşehir Belediyesi/));
    expect(useMunicipalFilterStore.getState().source).toBe('izum');
    expect(mapRenderCount).toBeLessThanOrEqual(RENDER_BUDGET);

    // Source section "Tümü" is the first All chip.
    fireEvent.press(getAllByText('Tümü')[0]!);
    expect(useMunicipalFilterStore.getState().source).toBe('all');
    expect(mapRenderCount).toBeLessThanOrEqual(RENDER_BUDGET);
  });
});
