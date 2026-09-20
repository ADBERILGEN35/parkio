/**
 * Web product analytics seam (WP-SPA-12 + Y04 client.analytics.v1).
 * Closed union, privacy-safe params, consent-gated, fail-open transport.
 *
 * Vendor (PostHog HTTP capture) is OFF by default. Activation requires:
 * - user consent granted
 * - VITE_PRODUCT_ANALYTICS_VENDOR_ENABLED=true
 * - VITE_POSTHOG_KEY + VITE_POSTHOG_HOST
 *
 * No posthog-js SDK → no autocapture / session replay by construction.
 */

import {
  ProductAnalyticsClient,
  createMemoryStorage,
  createWebLocalStorage,
  type AnalyticsConsentState,
  type CapturedAnalyticsEvent,
  LocalCaptureTransport,
} from '@parkio/product-analytics';
import type { SpaTelemetryEventName, SpaTelemetryParams } from '@parkio/types';

export type ProductAnalyticsEventName = SpaTelemetryEventName;
export type ProductAnalyticsParams = SpaTelemetryParams;

type LegacyVendorTransport = (
  name: ProductAnalyticsEventName,
  params?: ProductAnalyticsParams,
) => void;

let client: ProductAnalyticsClient | null = null;
let legacyTransport: LegacyVendorTransport | null = null;
let bootstrapped = false;

function readEnv(name: string): string | undefined {
  try {
    const env = import.meta.env as Record<string, string | boolean | undefined>;
    const value = env[name];
    return typeof value === 'string' && value.length > 0 ? value : undefined;
  } catch {
    return undefined;
  }
}

function ensureClient(): ProductAnalyticsClient {
  if (client) return client;
  const vendorEnabled = readEnv('VITE_PRODUCT_ANALYTICS_VENDOR_ENABLED') === 'true';
  const apiKey = readEnv('VITE_POSTHOG_KEY');
  const host = readEnv('VITE_POSTHOG_HOST');
  client = new ProductAnalyticsClient({
    platform: 'web',
    storage: typeof localStorage === 'undefined' ? createMemoryStorage() : createWebLocalStorage(),
    vendorEnabled,
    posthog: apiKey && host ? { apiKey, host } : undefined,
    idleTimeoutMs: 60_000,
    heartbeatIntervalMs: 0,
    appVersion: readEnv('VITE_APP_VERSION'),
  });
  return client;
}

/** Call once from bootstrap. Safe to call repeatedly. */
export async function initProductAnalytics(): Promise<void> {
  if (bootstrapped) return;
  bootstrapped = true;
  await ensureClient().init();
}

export function getProductAnalyticsClient(): ProductAnalyticsClient {
  return ensureClient();
}

export function setProductAnalyticsTransport(transport: LegacyVendorTransport): void {
  legacyTransport = transport;
}

export function useLocalProductAnalyticsCapture(): LocalCaptureTransport {
  return ensureClient().useLocalCapture();
}

export function resetProductAnalyticsForTests(): void {
  client?.dispose();
  client = null;
  legacyTransport = null;
  bootstrapped = false;
}

export function getQueuedProductAnalyticsForTests(): readonly CapturedAnalyticsEvent[] {
  return ensureClient().getQueuedForTests();
}

export async function setProductAnalyticsConsent(
  consent: AnalyticsConsentState,
): Promise<void> {
  await ensureClient().setConsent(consent);
}

export function getProductAnalyticsConsent(): AnalyticsConsentState {
  return ensureClient().getConsent();
}

export async function identifyProductAnalyticsUser(
  pseudonymousDistinctId: string,
): Promise<void> {
  await ensureClient().identify(pseudonymousDistinctId);
}

export async function resetProductAnalyticsIdentity(): Promise<void> {
  await ensureClient().resetIdentity();
}

/**
 * Track a product event. Privacy violations drop the event (fail-open) unless
 * `strict: true` (unit tests). Consent-gated via ProductAnalyticsClient.
 */
export function trackProductEvent(
  name: ProductAnalyticsEventName,
  params?: ProductAnalyticsParams,
  options?: { strict?: boolean },
): void {
  try {
    ensureClient().track(name, params, options);
    if (legacyTransport && ensureClient().getConsent() === 'granted') {
      try {
        legacyTransport(name, params);
      } catch {
        // fail-open
      }
    }
  } catch (error) {
    if (options?.strict) throw error;
  }
}

export function trackScreenViewed(pathname: string): void {
  ensureClient().trackScreenViewed(pathname);
}

export function noteAnalyticsInteraction(): void {
  ensureClient().noteInteraction();
}

export function setAnalyticsForeground(active: boolean): void {
  ensureClient().setForeground(active);
}

export function setAnalyticsFocused(focused: boolean): void {
  ensureClient().setFocused(focused);
}
