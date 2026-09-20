/**
 * Mobile-v2 product analytics seam (S1-P0-10 + WP-SPA-12 + Y04).
 * Closed union — intentional client interaction + SPA funnel events.
 *
 * Never attach precise coordinates, maps URLs, share message text, session
 * UUIDs, tokens, or free-text errors as parameters.
 *
 * Backend Kafka remains authoritative for domain ParkingSession lifecycle;
 * client `parking_session_*` events are privacy-safe funnel proxies only.
 *
 * Vendor (PostHog HTTP) OFF by default. No RN PostHog SDK → no autocapture /
 * session replay by construction. EXPO_PUBLIC_PRODUCT_ANALYTICS_* gates vendor.
 */

import {
  ProductAnalyticsClient,
  type AnalyticsConsentState,
  type AnalyticsStorage,
  LocalCaptureTransport,
} from '@parkio/product-analytics';
import {
  SPA_TELEMETRY_EVENT_NAMES,
  type SpaTelemetryEventName,
  type SpaTelemetryParams,
} from '@parkio/types';
import { sanitizeSpaTelemetryParams } from '@parkio/validation';
import Constants from 'expo-constants';
import { readJson, removeJson, writeJson } from '@/services/jsonStore';

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

type VendorTransport = (name: ProductAnalyticsEventName, params?: ProductAnalyticsParams) => void;

const SPA_NAME_SET = new Set<string>(SPA_TELEMETRY_EVENT_NAMES as readonly string[]);
const STORAGE_BAG_KEY = 'product-analytics-v1';

let client: ProductAnalyticsClient | null = null;
let vendorTransport: VendorTransport | null = null;
let bootstrapped = false;

function createJsonStoreAdapter(): AnalyticsStorage {
  type Bag = Record<string, string>;
  async function load(): Promise<Bag> {
    return (await readJson<Bag>(STORAGE_BAG_KEY)) ?? {};
  }
  return {
    getItem: async (key) => {
      const bag = await load();
      return bag[key] ?? null;
    },
    setItem: async (key, value) => {
      const bag = await load();
      bag[key] = value;
      await writeJson(STORAGE_BAG_KEY, bag);
    },
    removeItem: async (key) => {
      const bag = await load();
      if (key in bag) {
        delete bag[key];
        if (Object.keys(bag).length === 0) await removeJson(STORAGE_BAG_KEY);
        else await writeJson(STORAGE_BAG_KEY, bag);
      }
    },
  };
}

function readExtra(name: string): string | undefined {
  try {
    const extra = Constants.expoConfig?.extra as Record<string, unknown> | undefined;
    const fromExtra = extra?.[name];
    if (typeof fromExtra === 'string' && fromExtra.length > 0) return fromExtra;
  } catch {
    // ignore
  }
  try {
    const env = process.env as Record<string, string | undefined>;
    const value = env[name];
    return typeof value === 'string' && value.length > 0 ? value : undefined;
  } catch {
    return undefined;
  }
}

function ensureClient(): ProductAnalyticsClient {
  if (client) return client;
  const vendorEnabled = readExtra('EXPO_PUBLIC_PRODUCT_ANALYTICS_VENDOR_ENABLED') === 'true';
  const apiKey = readExtra('EXPO_PUBLIC_POSTHOG_KEY');
  const host = readExtra('EXPO_PUBLIC_POSTHOG_HOST');
  const allowTestSink = readExtra('EXPO_PUBLIC_PRODUCT_ANALYTICS_ALLOW_TEST_SINK') === 'true';
  client = new ProductAnalyticsClient({
    platform: 'mobile_v2',
    storage: createJsonStoreAdapter(),
    vendorEnabled,
    allowTestSink,
    posthog: apiKey && host ? { apiKey, host } : undefined,
    idleTimeoutMs: 60_000,
    heartbeatIntervalMs: 0,
    appVersion: Constants.expoConfig?.version,
  });
  return client;
}

export async function initProductAnalytics(): Promise<void> {
  if (bootstrapped) return;
  bootstrapped = true;
  const c = ensureClient();
  if (readExtra('EXPO_PUBLIC_PRODUCT_ANALYTICS_ALLOW_TEST_SINK') === 'true') {
    c.useLocalCapture();
  }
  await c.init();
}

export function getProductAnalyticsClient(): ProductAnalyticsClient {
  return ensureClient();
}

/** Wire a legacy vendor callback. Flushes are consent-gated by the client. */
export function setProductAnalyticsTransport(transport: VendorTransport): void {
  vendorTransport = transport;
}

export function useLocalProductAnalyticsCapture(): LocalCaptureTransport {
  return ensureClient().useLocalCapture();
}

/** Test helper — clears queue and vendor wiring. */
export function resetProductAnalyticsForTests(): void {
  client?.dispose();
  client = null;
  vendorTransport = null;
  bootstrapped = false;
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

export function trackProductEvent(
  name: ProductAnalyticsEventName,
  params?: ProductAnalyticsParams,
  options?: { strict?: boolean },
): void {
  try {
    if (SPA_NAME_SET.has(name)) {
      const { platform: _legacyPlatform, action: _a, reason: _r, ...spaParams } = params ?? {};
      void _legacyPlatform;
      void _a;
      void _r;
      const sanitized = sanitizeSpaTelemetryParams(spaParams as SpaTelemetryParams);
      ensureClient().track(
        name,
        {
          ...(sanitized ?? {}),
          platform: 'mobile_v2',
        },
        options,
      );
    } else {
      assertLegacyPrivacySafeParams(params);
      if (ensureClient().getConsent() !== 'granted') return;
      if (vendorTransport) {
        try {
          vendorTransport(name, params);
        } catch {
          // fail-open
        }
      }
      return;
    }
    if (vendorTransport && ensureClient().getConsent() === 'granted') {
      try {
        vendorTransport(name, params);
      } catch {
        // fail-open
      }
    }
  } catch (error) {
    if (options?.strict) throw error;
  }
}

export function trackScreenViewed(route: string): void {
  ensureClient().trackScreenViewed(route);
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
