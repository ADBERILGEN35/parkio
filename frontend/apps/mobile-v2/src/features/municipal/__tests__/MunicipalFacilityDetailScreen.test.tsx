import type { MunicipalFacility } from '@parkio/types';
import { fireEvent } from '@testing-library/react-native';
import { NotFoundError } from '@parkio/api-client';
import { renderWithProviders } from '@/test/renderWithProviders';

const mockFacilityId = '70db58f2-4cca-4010-9315-fa46b30fba1e';
const mockMunicipalDiscovery = jest.fn(() => true);
const mockUseQuery = jest.fn();
const mockBack = jest.fn();
const mockParams = jest.fn(() => ({ id: mockFacilityId }));

jest.mock('@/config/env', () => ({
  appConfig: {
    appEnv: 'development',
    isProductionLike: false,
    apiBaseUrl: 'http://localhost:8080/api/v1',
    get features() {
      return {
        smartReturn: true,
        municipalDiscovery: mockMunicipalDiscovery(),
      };
    },
  },
}));

jest.mock('@tanstack/react-query', () => {
  const actual = jest.requireActual('@tanstack/react-query');
  return {
    ...actual,
    useQuery: (...args: unknown[]) => mockUseQuery(...args),
  };
});

jest.mock('expo-router', () => ({
  Redirect: ({ href }: { href: string }) => {
    const { Text } = require('react-native');
    return <Text testID="redirect">{href}</Text>;
  },
  useLocalSearchParams: () => mockParams(),
  useRouter: () => ({ back: mockBack, push: jest.fn() }),
}));

jest.mock('@/providers/ToastProvider', () => ({
  useToast: () => ({ show: jest.fn() }),
}));

jest.mock('expo-linking', () => ({
  canOpenURL: jest.fn(async () => true),
  openURL: jest.fn(async () => undefined),
}));

import { MunicipalFacilityDetailScreen } from '../MunicipalFacilityDetailScreen';

function makeFacility(overrides: Partial<MunicipalFacility> = {}): MunicipalFacility {
  return {
    id: mockFacilityId,
    displayName: 'Konak Otopark',
    operatorName: null,
    facilityType: 'OFF_STREET',
    addressText: 'Konak, İzmir',
    latitude: 38.4237,
    longitude: 27.1428,
    capacityTotal: 59,
    availableSpaces: 1,
    occupiedSpaces: 58,
    freshness: 'LIVE',
    attribution: 'OpenStreetMap contributors',
    sourceLabel: 'IZUM',
    lastUpdatedAt: new Date().toISOString(),
    contributingSourceKeys: ['izmir-izum-otoparklar'],
    selectedFieldProvenanceSummary: null,
    registryConfidenceOrReviewStatus: null,
    availabilitySource: 'izmir-izum-otoparklar',
    availabilityFreshness: 'LIVE',
    availabilityObservationTimestamp: new Date().toISOString(),
    ...overrides,
  };
}

describe('MunicipalFacilityDetailScreen', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockMunicipalDiscovery.mockReturnValue(true);
    mockParams.mockReturnValue({ id: mockFacilityId });
  });

  it('redirects when municipal discovery flag is off', () => {
    mockMunicipalDiscovery.mockReturnValue(false);
    mockUseQuery.mockReturnValue({
      data: undefined,
      isPending: false,
      isError: false,
      error: null,
      refetch: jest.fn(),
    });
    const { getByTestId } = renderWithProviders(<MunicipalFacilityDetailScreen />);
    expect(getByTestId('redirect').props.children).toBe('/(main)/(tabs)/map');
  });

  it('shows not-found for blank id without treating it as a network error', () => {
    mockParams.mockReturnValue({ id: '   ' });
    mockUseQuery.mockReturnValue({
      data: undefined,
      isPending: false,
      isError: false,
      error: null,
      refetch: jest.fn(),
    });
    const { getByText, queryByText } = renderWithProviders(<MunicipalFacilityDetailScreen />);
    expect(getByText('Tesis bulunamadı')).toBeTruthy();
    expect(queryByText('Tekrar dene')).toBeNull();
  });

  it('renders live İZUM detail with metrics and canonical source', () => {
    mockUseQuery.mockReturnValue({
      data: makeFacility(),
      isPending: false,
      isError: false,
      error: null,
      refetch: jest.fn(),
    });
    const { getByText, getAllByText, queryByText } = renderWithProviders(<MunicipalFacilityDetailScreen />);
    expect(getAllByText('Konak Otopark').length).toBeGreaterThan(0);
    expect(getAllByText(/İzmir Büyükşehir Belediyesi \/ İZUM/).length).toBeGreaterThan(0);
    expect(getByText('Boş yer')).toBeTruthy();
    expect(getByText('1')).toBeTruthy();
    expect(getByText('Dolu')).toBeTruthy();
    expect(getByText('58')).toBeTruthy();
    expect(getByText('Toplam kapasite')).toBeTruthy();
    expect(getByText('59')).toBeTruthy();
    expect(getByText('Konak, İzmir')).toBeTruthy();
    expect(getByText('Haritada aç')).toBeTruthy();
    expect(queryByText(/osm-geofabrik/)).toBeNull();
    expect(queryByText(/izmir-izum/)).toBeNull();
    expect(queryByText('38.4237')).toBeNull();
    expect(queryByText('N/A')).toBeNull();
    expect(queryByText('Unknown')).toBeNull();
  });

  it('renders stale İZUM without numeric occupancy as current', () => {
    mockUseQuery.mockReturnValue({
      data: makeFacility({
        availableSpaces: null,
        occupiedSpaces: null,
        freshness: 'STALE',
        availabilityFreshness: 'STALE',
      }),
      isPending: false,
      isError: false,
      error: null,
      refetch: jest.fn(),
    });
    const { getByText, queryByText } = renderWithProviders(<MunicipalFacilityDetailScreen />);
    expect(getByText('Canlı veri geçici olarak güncel değil')).toBeTruthy();
    expect(queryByText('Boş yer')).toBeNull();
  });

  it('renders static OSM without occupancy metrics', () => {
    mockUseQuery.mockReturnValue({
      data: makeFacility({
        availableSpaces: null,
        occupiedSpaces: null,
        capacityTotal: 120,
        freshness: 'UNAVAILABLE',
        availabilityFreshness: 'UNAVAILABLE',
        contributingSourceKeys: ['osm-geofabrik-turkey'],
        sourceLabel: 'OpenStreetMap',
        availabilitySource: null,
      }),
      isPending: false,
      isError: false,
      error: null,
      refetch: jest.fn(),
    });
    const { getByText, getAllByText, queryByText } = renderWithProviders(<MunicipalFacilityDetailScreen />);
    expect(getByText('Canlı doluluk paylaşılmıyor')).toBeTruthy();
    expect(getAllByText('OpenStreetMap').length).toBeGreaterThan(0);
    expect(queryByText('Boş yer')).toBeNull();
  });

  it('shows retry on non-404 errors', () => {
    const refetch = jest.fn();
    mockUseQuery.mockReturnValue({
      data: undefined,
      isPending: false,
      isError: true,
      error: new Error('boom'),
      refetch,
    });
    const { getByText } = renderWithProviders(<MunicipalFacilityDetailScreen />);
    fireEvent.press(getByText('Tekrar dene'));
    expect(refetch).toHaveBeenCalled();
  });

  it('maps 404 to not-found', () => {
    mockUseQuery.mockReturnValue({
      data: undefined,
      isPending: false,
      isError: true,
      error: new NotFoundError({
        code: 'NOT_FOUND',
        message: 'missing',
        traceId: 't',
        timestamp: new Date().toISOString(),
      } as never),
      refetch: jest.fn(),
    });
    const { getByText, queryByText } = renderWithProviders(<MunicipalFacilityDetailScreen />);
    expect(getByText('Tesis bulunamadı')).toBeTruthy();
    expect(queryByText('Tekrar dene')).toBeNull();
  });
});
