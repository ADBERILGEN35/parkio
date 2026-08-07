import { describe, expect, it, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import type { ParkingCandidate } from '@parkio/types';
import { RecommendationCard } from '@/features/smart-parking-assistant/RecommendationCard';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, opts?: Record<string, unknown>) => {
      if (key === 'assistant.rankRecommended') return 'Önerilen';
      if (key === 'assistant.rankOption') return `${opts?.n}. seçenek`;
      if (key === 'assistant.channelMunicipal') return 'Belediye';
      if (key === 'assistant.channelCommunity') return 'Topluluk';
      if (key === 'assistant.distanceMeters') return `${opts?.meters} m`;
      if (key === 'assistant.reasons.closeToDestination') return 'Hedefe yakın';
      if (key === 'assistant.reasons.liveAvailability') return 'Canlı doluluk verisi var';
      if (key === 'assistant.freshnessLive') return 'Canlı doluluk';
      if (key === 'assistant.spacesAvailable') return `${opts?.available} / ${opts?.capacity} boş`;
      if (key === 'assistant.reasonsAria') return 'reasons';
      return key;
    },
  }),
}));

const candidate: ParkingCandidate = {
  id: 'cand-1',
  channel: 'MUNICIPAL_FACILITY',
  refId: 'fac-1',
  title: 'İZUM Konak Otoparkı',
  latitude: 38.41,
  longitude: 27.12,
  distanceMeters: 240,
  baselineOrder: 0,
  sourceLabel: 'İZUM',
  availability: {
    kind: 'MUNICIPAL',
    freshness: 'LIVE',
    availableSpaces: 12,
    capacityTotal: 100,
  },
  reasons: [
    { code: 'CLOSE_TO_DESTINATION' },
    { code: 'LIVE_AVAILABILITY' },
  ],
  score: 0.9,
  scoreBreakdown: {
    distance: 1,
    freshness: 1,
    capacity: 1,
    confidence: 1,
    favourite: 0,
  },
  rankingVersion: 'DETERMINISTIC_V1',
};

describe('RecommendationCard', () => {
  it('shows product fields and hides raw score', () => {
    const onSelect = vi.fn();
    render(
      <RecommendationCard
        candidate={candidate}
        rankIndex={0}
        selected={false}
        onSelect={onSelect}
      />,
    );

    expect(screen.getByText('Önerilen')).toBeInTheDocument();
    expect(screen.getByText('İZUM Konak Otoparkı')).toBeInTheDocument();
    expect(screen.getByText('Hedefe yakın')).toBeInTheDocument();
    expect(screen.getByText('Canlı doluluk verisi var')).toBeInTheDocument();
    expect(screen.queryByText('0.9')).not.toBeInTheDocument();
    expect(screen.queryByText(/scoreBreakdown/i)).not.toBeInTheDocument();
    expect(screen.queryByText('fac-1')).not.toBeInTheDocument();

    fireEvent.click(screen.getByTestId('assistant-recommendation-card'));
    expect(onSelect).toHaveBeenCalledWith(candidate);
  });

  it('preserves server rank label for later options', () => {
    render(
      <RecommendationCard
        candidate={candidate}
        rankIndex={2}
        selected
        onSelect={vi.fn()}
      />,
    );
    expect(screen.getByText('3. seçenek')).toBeInTheDocument();
    expect(screen.getByTestId('assistant-recommendation-card')).toHaveAttribute(
      'aria-pressed',
      'true',
    );
  });
});
