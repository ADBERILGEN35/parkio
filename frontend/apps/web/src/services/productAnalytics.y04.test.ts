/**
 * Web product analytics acceptance: consent gate + local capture (no vendor).
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import {
  getProductAnalyticsConsent,
  initProductAnalytics,
  resetProductAnalyticsForTests,
  setProductAnalyticsConsent,
  trackProductEvent,
  useLocalProductAnalyticsCapture,
} from './productAnalytics';

describe('web productAnalytics Y04', () => {
  beforeEach(async () => {
    resetProductAnalyticsForTests();
    await initProductAnalytics();
  });

  afterEach(() => {
    resetProductAnalyticsForTests();
  });

  it('disabled by default — no outbound local events', async () => {
    const capture = useLocalProductAnalyticsCapture();
    expect(getProductAnalyticsConsent()).toBe('unset');
    trackProductEvent('map_ready');
    expect(capture.events).toHaveLength(0);
  });

  it('activation permits approved events only after consent', async () => {
    const capture = useLocalProductAnalyticsCapture();
    await setProductAnalyticsConsent('granted');
    trackProductEvent('map_ready');
    trackProductEvent('filter_applied', { filterKind: 'availability' });
    expect(capture.events.some((e) => e.name === 'map_ready')).toBe(true);
    expect(capture.events.some((e) => e.name === 'filter_applied')).toBe(true);
  });

  it('opt-out stops further capture', async () => {
    const capture = useLocalProductAnalyticsCapture();
    await setProductAnalyticsConsent('granted');
    trackProductEvent('map_ready');
    const n = capture.events.length;
    await setProductAnalyticsConsent('denied');
    trackProductEvent('map_ready');
    expect(capture.events.filter((e) => e.name === 'map_ready').length).toBe(
      capture.events.slice(0, n).filter((e) => e.name === 'map_ready').length,
    );
  });
});
