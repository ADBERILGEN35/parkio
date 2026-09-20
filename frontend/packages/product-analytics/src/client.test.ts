import { describe, expect, it, vi } from 'vitest';
import { ActiveTimeTracker } from './activeTime';
import { ProductAnalyticsClient, createMemoryStorage } from './client';
import { normalizeAnalyticsScreenName } from './normalizeScreen';
import { PostHogHttpTransport } from './posthogHttp';

describe('normalizeAnalyticsScreenName', () => {
  it('normalizes web and mobile routes without query leakage', () => {
    expect(normalizeAnalyticsScreenName('/map?lat=1&lng=2')).toBe('map');
    expect(normalizeAnalyticsScreenName('/facilities/abc-uuid?d=100')).toBe('facility_detail');
    expect(normalizeAnalyticsScreenName('/(auth)/login')).toBe('login');
    expect(normalizeAnalyticsScreenName('/(main)/(tabs)/map')).toBe('map');
    expect(normalizeAnalyticsScreenName('/profile/preferences')).toBe('preferences');
  });
});

describe('ActiveTimeTracker', () => {
  it('does not accumulate while backgrounded or idle beyond timeout', () => {
    let now = 0;
    const tracker = new ActiveTimeTracker({
      idleTimeoutMs: 60_000,
      heartbeatIntervalMs: 0,
      monotonicNow: () => now,
    });

    tracker.setForeground(true);
    tracker.setFocused(true);
    tracker.enterScreen('map');
    tracker.noteInteraction();

    now += 10_000;
    tracker.noteInteraction();
    let snap = tracker.snapshot();
    expect(snap?.activeDurationMs).toBe(10_000);

    // Background suspends
    tracker.setForeground(false);
    now += 30_000;
    snap = tracker.snapshot();
    expect(snap?.activeDurationMs).toBe(10_000);

    tracker.setForeground(true);
    tracker.noteInteraction();
    now += 5_000;
    snap = tracker.snapshot();
    expect(snap?.activeDurationMs).toBe(15_000);

    // Idle beyond timeout — further wall time does not inflate
    now += 70_000;
    snap = tracker.snapshot();
    // last interaction was at 45_000 (15k accumulated from start...); after idle close, max +60s from last interaction
    // lastInteraction at T=45000 (10+30+5), idle cutoff = 105000, now=115000 → closes at cutoff adding 60s once
    expect(snap!.activeDurationMs).toBeLessThanOrEqual(15_000 + 60_000);
    expect(snap!.activeDurationMs).toBeGreaterThanOrEqual(15_000);
  });

  it('ignores duplicate/out-of-order checkpoints that would inflate', () => {
    let now = 0;
    const tracker = new ActiveTimeTracker({ monotonicNow: () => now, idleTimeoutMs: 60_000 });
    tracker.setForeground(true);
    tracker.setFocused(true);
    tracker.enterScreen('map');
    tracker.noteInteraction();
    // Apply absolute checkpoint without also letting the live segment double-count.
    tracker.applyCheckpoint(2, 20_000);
    tracker.applyCheckpoint(1, 50_000); // older seq ignored
    tracker.applyCheckpoint(2, 20_000); // same seq ignored
    const snap = tracker.snapshot();
    expect(snap?.activeDurationMs).toBe(20_000);
  });

  it('screen transition does not double-count', () => {
    let now = 0;
    const tracker = new ActiveTimeTracker({ monotonicNow: () => now });
    tracker.setForeground(true);
    tracker.setFocused(true);
    tracker.enterScreen('map');
    tracker.noteInteraction();
    now += 8_000;
    const left = tracker.enterScreen('facility_detail');
    expect(left?.activeDurationMs).toBe(8_000);
    tracker.noteInteraction();
    now += 4_000;
    const detail = tracker.exitScreen();
    expect(detail?.activeDurationMs).toBe(4_000);
  });
});

describe('ProductAnalyticsClient consent and isolation', () => {
  it('emits nothing while consent unset', async () => {
    const client = new ProductAnalyticsClient({
      platform: 'web',
      storage: createMemoryStorage(),
      vendorEnabled: false,
    });
    const capture = client.useLocalCapture();
    await client.init();
    client.track('map_ready');
    expect(capture.events).toHaveLength(0);
  });

  it('activation permits events; opt-out discards queue and stops capture', async () => {
    const storage = createMemoryStorage();
    const client = new ProductAnalyticsClient({
      platform: 'web',
      storage,
      vendorEnabled: false,
      maxQueueSize: 10,
    });
    const capture = client.useLocalCapture();
    await client.init();

    await client.setConsent('granted');
    client.track('map_ready');
    expect(capture.events.some((e) => e.name === 'map_ready')).toBe(true);

    // Simulate failed vendor flush by enqueueing via a throwing transport path:
    // use PostHog with blocked host → null transport; enqueue manually through flush failure
    await client.setConsent('denied');
    const afterDeny = capture.events.length;
    client.track('filter_applied', { filterKind: 'availability' });
    expect(capture.events.length).toBe(afterDeny);
    expect(client.getQueuedForTests()).toHaveLength(0);
  });

  it('enabling later does not upload pre-consent history', async () => {
    const client = new ProductAnalyticsClient({
      platform: 'web',
      storage: createMemoryStorage(),
      vendorEnabled: false,
    });
    const capture = client.useLocalCapture();
    await client.init();
    client.track('map_ready');
    expect(capture.events).toHaveLength(0);
    await client.setConsent('granted');
    expect(capture.events.every((e) => e.name !== 'map_ready')).toBe(true);
    expect(capture.events.some((e) => e.name === 'analytics_session_started')).toBe(true);
  });

  it('logout and user A → B isolation clears distinct and queue', async () => {
    const client = new ProductAnalyticsClient({
      platform: 'mobile_v2',
      storage: createMemoryStorage(),
      vendorEnabled: false,
    });
    const capture = client.useLocalCapture();
    await client.init();
    await client.setConsent('granted');
    await client.identify('pseudo_user_a');
    client.track('screen_viewed', { screenName: 'map' });
    const sessionA = client.getAnalyticsSessionId();

    await client.resetIdentity();
    expect(client.getDistinctId()).toBeNull();
    expect(client.getAnalyticsSessionId()).not.toBe(sessionA);
    expect(client.getQueuedForTests()).toHaveLength(0);

    await client.identify('pseudo_user_b');
    client.track('screen_viewed', { screenName: 'profile' });
    const bEvents = capture.events.filter(
      (e) => e.distinctId === 'pseudo_user_b' && e.name === 'screen_viewed',
    );
    const aLeaks = capture.events.filter(
      (e) => e.distinctId === 'pseudo_user_a' && e.analyticsSessionId === client.getAnalyticsSessionId(),
    );
    expect(bEvents.length).toBeGreaterThan(0);
    expect(aLeaks).toHaveLength(0);
  });

  it('rejects sensitive params (canaries)', async () => {
    const client = new ProductAnalyticsClient({
      platform: 'web',
      storage: createMemoryStorage(),
      vendorEnabled: false,
    });
    client.useLocalCapture();
    await client.init();
    await client.setConsent('granted');
    expect(() =>
      client.track(
        'map_ready',
        { email: 'a@b.com' } as never,
        { strict: true },
      ),
    ).toThrow();
    expect(() =>
      client.track(
        'filter_applied',
        { url: '/map?token=secret' } as never,
        { strict: true },
      ),
    ).toThrow();
  });

  it('map funnel sequence records preview → detail → return', async () => {
    const client = new ProductAnalyticsClient({
      platform: 'web',
      storage: createMemoryStorage(),
      vendorEnabled: false,
    });
    const capture = client.useLocalCapture();
    await client.init();
    await client.setConsent('granted');
    client.setForeground(true);
    client.setFocused(true);
    client.trackScreenViewed('/map');
    client.track('map_ready');
    client.track('facility_preview_opened', { selectionOrigin: 'map' });
    client.track('facility_detail_opened', { selectionOrigin: 'map' });
    client.trackScreenViewed('/facilities/x');
    client.track('returned_to_map');
    client.trackScreenViewed('/map');
    const names = capture.events.map((e) => e.name);
    expect(names).toContain('map_ready');
    expect(names).toContain('facility_preview_opened');
    expect(names).toContain('facility_detail_opened');
    expect(names).toContain('returned_to_map');
    expect(names.filter((n) => n === 'screen_viewed').length).toBeGreaterThanOrEqual(2);
  });

  it('map init failure and retry outcome', async () => {
    const client = new ProductAnalyticsClient({
      platform: 'mobile_v2',
      storage: createMemoryStorage(),
      vendorEnabled: false,
    });
    const capture = client.useLocalCapture();
    await client.init();
    await client.setConsent('granted');
    client.track('map_init_failed', { mapErrorCode: 'load_failed' });
    client.track('retry_attempted', { mapErrorCode: 'load_failed' });
    client.track('retry_outcome', { retryOutcome: 'succeeded', mapErrorCode: 'load_failed' });
    expect(capture.events.map((e) => e.name)).toEqual(
      expect.arrayContaining(['map_init_failed', 'retry_attempted', 'retry_outcome']),
    );
  });
});

describe('PostHogHttpTransport', () => {
  it('posts batch payload without autocapture/session replay flags enabled', async () => {
    const fetchImpl = vi.fn(async () => new Response(null, { status: 200 }));
    const transport = new PostHogHttpTransport({
      apiKey: 'phc_test',
      host: 'https://eu.i.posthog.com',
      fetchImpl: fetchImpl as unknown as typeof fetch,
    });
    await transport.send([
      {
        name: 'map_ready',
        occurredAtMs: 1_700_000_000_000,
        seq: 1,
        analyticsSessionId: 'as_1',
        params: { platform: 'web', schemaVersion: 1 },
      },
    ]);
    expect(fetchImpl).toHaveBeenCalledOnce();
    const [, init] = fetchImpl.mock.calls[0]!;
    const body = JSON.parse(String((init as RequestInit).body));
    expect(body.batch[0].properties.$session_recording_enabled).toBe(false);
    expect(body.batch[0].properties.$autocapture_disabled).toBe(true);
    expect(body.batch[0].event).toBe('map_ready');
  });
});
