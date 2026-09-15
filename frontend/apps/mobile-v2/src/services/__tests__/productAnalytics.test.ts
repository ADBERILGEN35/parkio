import {
  resetProductAnalyticsForTests,
  setProductAnalyticsTransport,
  trackProductEvent,
} from '../productAnalytics';

describe('productAnalytics privacy', () => {
  beforeEach(() => {
    resetProductAnalyticsForTests();
  });

  it('accepts coarse interaction params', () => {
    const transport = jest.fn();
    setProductAnalyticsTransport(transport);
    const prevDev = (global as { __DEV__?: boolean }).__DEV__;
    (global as { __DEV__?: boolean }).__DEV__ = false;

    trackProductEvent('return_to_car_clicked', { platform: 'ios' }, { strict: true });
    trackProductEvent('parking_location_shared', { platform: 'android' }, { strict: true });
    trackProductEvent(
      'parking_action_failed',
      {
        platform: 'ios',
        action: 'navigation',
        reason: 'platform_open_failed',
      },
      { strict: true },
    );

    expect(transport).toHaveBeenCalledTimes(3);
    (global as { __DEV__?: boolean }).__DEV__ = prevDev;
  });

  it('rejects coordinate-like or forbidden parameter keys in strict mode', () => {
    expect(() =>
      trackProductEvent('return_to_car_clicked', { latitude: 1 } as never, { strict: true }),
    ).toThrow(/Forbidden/);
    expect(() =>
      trackProductEvent('parking_location_shared', { url: 'https://x' } as never, {
        strict: true,
      }),
    ).toThrow(/Forbidden/);
    expect(() =>
      trackProductEvent(
        'parking_action_failed',
        {
          platform: '41.0,28.9',
        },
        { strict: true },
      ),
    ).toThrow(/Forbidden/);
  });

  it('allows SPA funnel names as product events', () => {
    const transport = jest.fn();
    setProductAnalyticsTransport(transport);
    (global as { __DEV__?: boolean }).__DEV__ = false;
    trackProductEvent('parking_session_started', {
      platform: 'mobile_v2',
      originSurface: 'map_location',
    });
    expect(transport).toHaveBeenCalledWith(
      'parking_session_started',
      expect.objectContaining({ originSurface: 'map_location' }),
    );
  });
});
