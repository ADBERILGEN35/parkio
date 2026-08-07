/**
 * Mobile-v2 product analytics seam (S1-P0-10 + WP-SPA-12).
 * Closed union — intentional client interaction + SPA funnel events.
 *
 * Never attach precise coordinates, maps URLs, share message text, session
 * UUIDs, tokens, or free-text errors as parameters.
 *
 * Backend Kafka remains authoritative for domain ParkingSession lifecycle;
 * client `parking_session_*` events are privacy-safe funnel proxies only
 * (no session IDs / coordinates).
 *
 * Transport: __DEV__ logs to console; release buffers into a queue that a
 * vendor SDK drains once wired.
 */

import {
  SPA_TELEMETRY_EVENT_NAMES,
  type SpaTelemetryEventName,
  type SpaTelemetryParams,
} from '@parkio/types';
import { sanitizeSpaTelemetryParams } from '@parkio/validation';

export type ProductAnalyticsEventName =
  | SpaTelemetryEventName
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
export type ProductAnalyticsParams = Omit<SpaTelemetryParams, 'platform'> & {
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

const SPA_NAME_SET = new Set<string>(SPA_TELEMETRY_EVENT_NAMES as readonly string[]);

/** Wire a vendor SDK. Flushes the in-memory release queue immediately. */
export function setProductAnalyticsTransport(transport: VendorTransport): void {
  vendorTransport = transport;
  for (const event of queue.splice(0)) {
    try {
      transport(event.name, event.params);
    } catch {
      // fail-open
    }
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
  options?: { strict?: boolean },
): void {
  let safe: ProductAnalyticsParams | undefined = params;
  try {
    if (SPA_NAME_SET.has(name)) {
      const { platform: _legacyPlatform, action: _a, reason: _r, ...spaParams } = params ?? {};
      void _legacyPlatform;
      void _a;
      void _r;
      const sanitized = sanitizeSpaTelemetryParams(spaParams as SpaTelemetryParams);
      safe = {
        ...(sanitized ?? {}),
        ...(params?.platform !== undefined ? { platform: params.platform } : {}),
      };
    } else {
      assertLegacyPrivacySafeParams(params);
    }
  } catch (error) {
    if (options?.strict) throw error;
    return;
  }

  try {
    if (vendorTransport) {
      vendorTransport(name, safe);
      return;
    }
    if (__DEV__) {
      console.info('[product-analytics]', name, safe ?? '');
      return;
    }
    queue.push({ name, params: safe, at: Date.now() });
    if (queue.length > MAX_QUEUED) {
      queue.shift();
    }
  } catch {
    // fail-open
  }
}

function assertLegacyPrivacySafeParams(params?: ProductAnalyticsParams): void {
  if (!params) return;
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
