import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  resetProductAnalyticsForTests,
  setProductAnalyticsTransport,
  trackProductEvent,
} from './productAnalytics';
import {
  resetSpaTelemetryForTests,
  trackAssistantOpened,
  trackDestinationConfirmed,
  trackNavigationStarted,
  trackRecommendationSelected,
  trackRecommendationsResponse,
} from './spaTelemetry';
import type { ParkingCandidate, RecommendationResponse } from '@parkio/types';

describe('web spa telemetry', () => {
  beforeEach(() => {
    resetProductAnalyticsForTests();
    resetSpaTelemetryForTests();
  });

  afterEach(() => {
    resetProductAnalyticsForTests();
    resetSpaTelemetryForTests();
  });

  it('emits assistant open and destination confirm without forbidden fields', () => {
    const transport = vi.fn();
    setProductAnalyticsTransport(transport);

    trackAssistantOpened();
    trackDestinationConfirmed('SEARCH');

    expect(transport).toHaveBeenCalled();
    const names = transport.mock.calls.map((c) => c[0]);
    expect(names).toContain('assistant_opened');
    expect(names).toContain('destination_confirmed');
    for (const call of transport.mock.calls) {
      const params = call[1] as Record<string, unknown>;
      expect(params).not.toHaveProperty('latitude');
      expect(params).not.toHaveProperty('userId');
      expect(params).not.toHaveProperty('label');
      expect(typeof params.journeyId).toBe('string');
    }
  });

  it('drops forbidden payloads fail-open without throwing', () => {
    expect(() =>
      trackProductEvent('assistant_opened', { latitude: 1 } as never),
    ).not.toThrow();
    expect(() =>
      trackProductEvent('assistant_opened', { latitude: 1 } as never, { strict: true }),
    ).toThrow(/Forbidden/);
  });

  it('emits recommendations_shown once per response identity', () => {
    const transport = vi.fn();
    setProductAnalyticsTransport(transport);

    const response: RecommendationResponse = {
      destination: {
        label: 'x',
        latitude: 1,
        longitude: 2,
        source: 'GEOCODING',
      },
      generatedAt: '2026-01-01T00:00:00Z',
      partial: false,
      inventoryStatus: 'OK',
      candidates: [
        {
          id: 'c1',
          channel: 'MUNICIPAL_FACILITY',
          refId: 'f1',
          title: 'A',
          latitude: 1,
          longitude: 2,
          distanceMeters: 10,
        } as ParkingCandidate,
      ],
      rankingStatus: 'APPLIED',
      rankingVersion: 'DETERMINISTIC_V1',
    };

    trackRecommendationsResponse(response);
    trackRecommendationsResponse(response);
    const shown = transport.mock.calls.filter((c) => c[0] === 'recommendations_shown');
    expect(shown).toHaveLength(1);
    expect(shown[0]![1]).not.toHaveProperty('refId');
  });

  it('records time_to_confident_choice on first selection/navigation', () => {
    const transport = vi.fn();
    setProductAnalyticsTransport(transport);

    trackDestinationConfirmed('HOME_QUICK_ACTION');
    trackNavigationStarted('MUNICIPAL_FACILITY');
    trackRecommendationSelected(
      {
        id: 'c1',
        channel: 'MUNICIPAL_FACILITY',
        refId: 'f1',
        title: 'A',
        latitude: 1,
        longitude: 2,
        distanceMeters: 10,
      } as ParkingCandidate,
      0,
    );

    const buckets = transport.mock.calls.filter((c) => c[0] === 'time_to_confident_choice');
    expect(buckets).toHaveLength(1);
  });
});
