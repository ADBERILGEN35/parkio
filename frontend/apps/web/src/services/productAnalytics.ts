/**
 * Web product analytics seam (WP-SPA-12).
 * Closed union, privacy-safe params, fail-open transport.
 */

import type { SpaTelemetryEventName, SpaTelemetryParams } from '@parkio/types';
import { sanitizeSpaTelemetryParams } from '@parkio/validation';

export type ProductAnalyticsEventName = SpaTelemetryEventName;

export type ProductAnalyticsParams = SpaTelemetryParams;

interface QueuedEvent {
  name: ProductAnalyticsEventName;
  params?: ProductAnalyticsParams;
  at: number;
}

const MAX_QUEUED = 100;
const queue: QueuedEvent[] = [];

type VendorTransport = (
  name: ProductAnalyticsEventName,
  params?: ProductAnalyticsParams,
) => void;
let vendorTransport: VendorTransport | null = null;

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

export function resetProductAnalyticsForTests(): void {
  queue.length = 0;
  vendorTransport = null;
}

export function getQueuedProductAnalyticsForTests(): readonly QueuedEvent[] {
  return queue;
}

/**
 * Track a product event. Privacy violations drop the event (fail-open) unless
 * `strict: true` (unit tests).
 */
export function trackProductEvent(
  name: ProductAnalyticsEventName,
  params?: ProductAnalyticsParams,
  options?: { strict?: boolean },
): void {
  let safe: ProductAnalyticsParams | undefined;
  try {
    safe = sanitizeSpaTelemetryParams(params);
  } catch (error) {
    if (options?.strict) throw error;
    return;
  }

  try {
    if (vendorTransport) {
      vendorTransport(name, safe);
      return;
    }
    if (import.meta.env.DEV) {
      console.info('[product-analytics]', name, safe ?? '');
      return;
    }
    queue.push({ name, params: safe, at: Date.now() });
    if (queue.length > MAX_QUEUED) queue.shift();
  } catch {
    // fail-open
  }
}
