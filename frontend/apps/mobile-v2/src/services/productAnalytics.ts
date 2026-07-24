/**
 * Mobile-v2 product analytics seam (S1-P0-10).
 * Closed union — only intentional client interaction events.
 *
 * Never attach precise coordinates, maps URLs, share message text, session
 * UUIDs, tokens, or free-text errors as parameters.
 *
 * Authoritative ParkingSession lifecycle facts (parking_session_started /
 * completed / cancelled) are backend-only and must not be emitted here.
 *
 * Transport: __DEV__ logs to console; release buffers into a queue that a
 * vendor SDK drains once wired (same pattern as legacy mobile analytics).
 */

export type ProductAnalyticsEventName =
  | 'return_to_car_clicked'
  | 'parking_location_shared'
  | 'parking_action_failed';

export type ParkingActionFailureReason =
  | 'invalid_destination'
  | 'unsupported_url'
  | 'platform_open_failed'
  | 'share_unavailable'
  | 'platform_share_failed'
  | 'unknown';

/** Coarse, non-identifying parameters only. */
export type ProductAnalyticsParams = {
  platform?: string;
  action?: 'navigation' | 'share';
  reason?: ParkingActionFailureReason;
};

interface QueuedEvent {
  name: ProductAnalyticsEventName;
  params?: ProductAnalyticsParams;
  at: number;
}

const MAX_QUEUED = 100;
const queue: QueuedEvent[] = [];

type VendorTransport = (name: ProductAnalyticsEventName, params?: ProductAnalyticsParams) => void;
let vendorTransport: VendorTransport | null = null;

/** Wire a vendor SDK. Flushes the in-memory release queue immediately. */
export function setProductAnalyticsTransport(transport: VendorTransport): void {
  vendorTransport = transport;
  for (const event of queue.splice(0)) {
    transport(event.name, event.params);
  }
}

/** Test helper — clears queue and vendor wiring. */
export function resetProductAnalyticsForTests(): void {
  queue.length = 0;
  vendorTransport = null;
}

export function trackProductEvent(
  name: ProductAnalyticsEventName,
  params?: ProductAnalyticsParams,
): void {
  assertPrivacySafeParams(params);
  if (__DEV__) {
    console.info('[product-analytics]', name, params ?? '');
    return;
  }
  if (vendorTransport) {
    vendorTransport(name, params);
    return;
  }
  queue.push({ name, params, at: Date.now() });
  if (queue.length > MAX_QUEUED) {
    queue.shift();
  }
}

function assertPrivacySafeParams(params?: ProductAnalyticsParams): void {
  if (!params) {
    return;
  }
  const forbiddenKeys = new Set([
    'latitude',
    'longitude',
    'lat',
    'lng',
    'lon',
    'coords',
    'coordinate',
    'url',
    'message',
    'sessionId',
    'session_id',
    'userId',
    'user_id',
    'token',
    'idempotencyKey',
    'idempotency_key',
  ]);
  for (const [key, value] of Object.entries(params)) {
    if (forbiddenKeys.has(key) || forbiddenKeys.has(key.toLowerCase())) {
      throw new Error(`Forbidden analytics parameter: ${key}`);
    }
    if (
      typeof value === 'string' &&
      (value.includes('maps://') ||
        value.includes('geo:') ||
        value.includes('openstreetmap') ||
        /^-?\d+\.\d+,-?\d+\.\d+$/.test(value))
    ) {
      throw new Error('Forbidden analytics parameter value');
    }
  }
}