import {
  resetProductAnalyticsForTests,
  setProductAnalyticsTransport,
  trackProductEvent,
} from '../productAnalytics';
import {
  resetSpaTelemetryForTests,
  trackAssistantOpened,
  trackDestinationConfirmed,
  trackQuickActionSelected,
  trackRecommendationsResponse,
  trackReturnToCarStarted,
} from '../spaTelemetry';
import type { RecommendationResponse } from '@parkio/types';

describe('mobile spa telemetry', () => {
  beforeEach(() => {
    resetProductAnalyticsForTests();
    resetSpaTelemetryForTests();
  });

  it('keeps legacy parking action events', () => {
    const transport = jest.fn();
    setProductAnalyticsTransport(transport);
    const prevDev = (global as { __DEV__?: boolean }).__DEV__;
    (global as { __DEV__?: boolean }).__DEV__ = false;

    trackProductEvent('return_to_car_clicked', { platform: 'ios' }, { strict: true });
    trackProductEvent('parking_location_shared', { platform: 'android' }, { strict: true });
    expect(transport).toHaveBeenCalledTimes(2);

    (global as { __DEV__?: boolean }).__DEV__ = prevDev;
  });

  it('rejects forbidden SPA params in strict mode and drops otherwise', () => {
    expect(() =>
      trackProductEvent('assistant_opened', { latitude: 1 } as never, { strict: true }),
    ).toThrow(/Forbidden/);
    expect(() =>
      trackProductEvent('assistant_opened', { latitude: 1 } as never),
    ).not.toThrow();
  });

  it('emits funnel events without identity fields', () => {
    const transport = jest.fn();
    setProductAnalyticsTransport(transport);
    (global as { __DEV__?: boolean }).__DEV__ = false;

    trackAssistantOpened();
    trackDestinationConfirmed('SEARCH');
    trackQuickActionSelected('HOME', 'AVAILABLE');
    trackReturnToCarStarted();

    const payload = JSON.stringify(transport.mock.calls);
    expect(payload).not.toMatch(/latitude|userId|facilityId|sessionId|label/i);
    expect(transport.mock.calls.map((c) => c[0])).toEqual(
      expect.arrayContaining([
        'assistant_opened',
        'destination_confirmed',
        'quick_action_selected',
        'return_to_car_started',
      ]),
    );
  });

  it('dedupes recommendations_shown', () => {
    const transport = jest.fn();
    setProductAnalyticsTransport(transport);
    (global as { __DEV__?: boolean }).__DEV__ = false;

    const response = {
      destination: { label: 'x', latitude: 1, longitude: 2, source: 'GEOCODING' as const },
      generatedAt: 't1',
      partial: true,
      inventoryStatus: 'DEGRADED' as const,
      candidates: [],
      rankingStatus: 'FALLBACK' as const,
    } as unknown as RecommendationResponse;

    trackRecommendationsResponse(response);
    trackRecommendationsResponse(response);
    expect(transport.mock.calls.filter((c) => c[0] === 'recommendations_empty')).toHaveLength(1);
    expect(transport.mock.calls.filter((c) => c[0] === 'ranking_fallback')).toHaveLength(1);
  });
});
