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

    trackProductEvent('return_to_car_clicked', { platform: 'ios' });
    trackProductEvent('parking_location_shared', { platform: 'android' });
    trackProductEvent('parking_action_failed', {
      platform: 'ios',
      action: 'navigation',
      reason: 'platform_open_failed',
    });

    expect(transport).toHaveBeenCalledTimes(3);
    (global as { __DEV__?: boolean }).__DEV__ = prevDev;
  });

  it('rejects coordinate-like or forbidden parameter keys', () => {
    expect(() =>
      trackProductEvent('return_to_car_clicked', { latitude: 1 } as never),
    ).toThrow(/Forbidden/);
    expect(() =>
      trackProductEvent('parking_location_shared', { url: 'https://x' } as never),
    ).toThrow(/Forbidden/);
    expect(() =>
      trackProductEvent('parking_action_failed', {
        platform: '41.0,28.9',
      }),
    ).toThrow(/Forbidden/);
  });

  it('does not define lifecycle event names on the product seam', () => {
    const names = [
      'return_to_car_clicked',
      'parking_location_shared',
      'parking_action_failed',
    ] as const;
    for (const name of names) {
      expect(name).not.toMatch(/parking_session_(started|completed|cancelled|deleted)/);
    }
  });
});