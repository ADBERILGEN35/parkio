import { render, fireEvent } from '@testing-library/react-native';
import type { ParkingCandidate } from '@parkio/types';
import { RecommendationCard } from '../RecommendationCard';

jest.mock('@/i18n/LocaleProvider', () => ({
  useT: () => (key: string, params?: Record<string, string | number>) => {
    if (key === 'assistant.rankOption' && params?.n != null) return `Option ${params.n}`;
    if (key === 'assistant.rankRecommended') return 'Recommended';
    if (key === 'assistant.channelMunicipal') return 'Municipal';
    if (key === 'assistant.channelCommunity') return 'Community';
    if (key === 'assistant.reasons.closeToDestination') return 'Close to destination';
    if (key === 'assistant.reasons.liveAvailability') return 'Live occupancy available';
    if (key === 'map.municipal.occupancy.live') return 'Live';
    if (key === 'map.municipal.spacesAvailable') {
      return `${params?.available} free · ${params?.capacity} capacity`;
    }
    if (key === 'parkedCar.parkHere.cta') return 'I parked here';
    return key;
  },
  useLocale: () => ({ locale: 'en' as const }),
}));

jest.mock('@/theme/ThemeProvider', () => ({
  useTheme: () => ({
    mode: 'light',
    colors: {
      primary: '#0050CB',
      primaryContainer: '#DCE9FF',
      onSurface: '#0B1C30',
      onSurfaceVariant: '#727687',
      outlineVariant: '#C0C6D4',
      surfaceContainer1: '#EFF4FF',
    },
  }),
}));

jest.mock('@/providers/ToastProvider', () => ({
  useToast: () => ({ show: jest.fn() }),
}));

jest.mock('@/features/parking/useParkHereAtTarget', () => ({
  useParkHereAtTarget: () => ({
    phase: 'idle',
    busy: false,
    start: jest.fn(async () => ({ status: 'success', session: {} })),
    reset: jest.fn(),
  }),
}));

const municipalCandidate: ParkingCandidate = {
  id: 'c1',
  channel: 'MUNICIPAL_FACILITY',
  refId: 'fac-1',
  title: 'Konak İZUM',
  latitude: 38.42,
  longitude: 27.13,
  distanceMeters: 220,
  baselineOrder: 0,
  availability: {
    kind: 'MUNICIPAL',
    freshness: 'LIVE',
    availableSpaces: 12,
    capacityTotal: 80,
  },
  sourceLabel: 'İZUM',
  reasons: [
    { code: 'CLOSE_TO_DESTINATION' },
    { code: 'LIVE_AVAILABILITY' },
  ],
  score: 0.91,
  scoreBreakdown: {
    distance: 0.5,
    freshness: 0.2,
    capacity: 0.1,
    confidence: 0.1,
    favourite: 0,
  },
  rankingVersion: 'DETERMINISTIC_V1',
};

describe('RecommendationCard', () => {
  it('shows recommended rank, reasons, and hides raw score', () => {
    const onSelect = jest.fn();
    const { getByTestId, getByText, queryByText } = render(
      <RecommendationCard
        candidate={municipalCandidate}
        rankIndex={0}
        selected={false}
        onSelect={onSelect}
      />,
    );
    expect(getByTestId('recommendation-card-c1')).toBeTruthy();
    expect(queryByText('0.91')).toBeNull();
    expect(queryByText('DETERMINISTIC_V1')).toBeNull();
    expect(queryByText('fac-1')).toBeNull();
    fireEvent.press(getByText('Konak İZUM'));
    expect(onSelect).toHaveBeenCalledWith(municipalCandidate);
  });

  it('preserves server order labels for later ranks', () => {
    const { getByText } = render(
      <RecommendationCard
        candidate={{ ...municipalCandidate, id: 'c2' }}
        rankIndex={2}
        selected
        onSelect={jest.fn()}
      />,
    );
    expect(getByText('Option 3')).toBeTruthy();
  });
});
