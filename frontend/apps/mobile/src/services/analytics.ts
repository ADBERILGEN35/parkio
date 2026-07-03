/**
 * Product analytics seam. Only the events below may be tracked — a closed union
 * keeps the surface intentional and reviewable. **Never** attach precise
 * coordinates, addresses, tokens, or free-text user content as parameters.
 *
 * Transport: development logs to the console; release builds buffer into a
 * small queue that a vendor SDK (Firebase Analytics) drains once wired in.
 * Firebase requires a native module + `google-services.json` and therefore an
 * EAS/Development build — see docs/beta/mobile-release.md.
 */

export type AnalyticsEventName =
  | 'login'
  | 'sign_up'
  | 'upload_started'
  | 'upload_completed'
  | 'upload_cancelled'
  | 'spot_created'
  | 'smart_return_opened'
  | 'notification_opened';

/** Coarse, non-identifying parameters only (no coordinates, no PII). */
export type AnalyticsParams = Record<string, string | number | boolean>;

interface QueuedEvent {
  name: AnalyticsEventName;
  params?: AnalyticsParams;
  at: number;
}

const MAX_QUEUED = 100;
const queue: QueuedEvent[] = [];

type VendorTransport = (name: AnalyticsEventName, params?: AnalyticsParams) => void;
let vendorTransport: VendorTransport | null = null;

/** Wire a vendor SDK (e.g. Firebase Analytics). Flushes the queue immediately. */
export function setAnalyticsTransport(transport: VendorTransport): void {
  vendorTransport = transport;
  for (const event of queue.splice(0)) transport(event.name, event.params);
}

export function track(name: AnalyticsEventName, params?: AnalyticsParams): void {
  if (__DEV__) {
    console.info('[analytics]', name, params ?? '');
    return;
  }
  if (vendorTransport) {
    vendorTransport(name, params);
    return;
  }
  queue.push({ name, params, at: Date.now() });
  if (queue.length > MAX_QUEUED) queue.shift();
}
