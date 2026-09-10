import type {
  SpaCountBucket,
  SpaLatencyBucket,
  SpaTelemetryEventName,
  SpaTelemetryParams,
} from '@parkio/types';
import { SPA_TELEMETRY_EVENT_NAMES } from '@parkio/types';

/** Forbidden analytics property names (case-insensitive match on keys). */
export const SPA_TELEMETRY_FORBIDDEN_KEYS = [
  'userid',
  'user_id',
  'email',
  'phone',
  'latitude',
  'longitude',
  'lat',
  'lng',
  'lon',
  'coords',
  'coordinate',
  'label',
  'address',
  'placeid',
  'place_id',
  'providerplaceid',
  'provider_place_id',
  'facilityid',
  'facility_id',
  'spotid',
  'spot_id',
  'sessionid',
  'session_id',
  'targetid',
  'target_id',
  'favouriteid',
  'favourite_id',
  'savedplaceid',
  'saved_place_id',
  'recentid',
  'recent_id',
  'searchquery',
  'search_query',
  'query',
  'token',
  'url',
  'message',
  'idempotencykey',
  'idempotency_key',
] as const;

const FORBIDDEN_KEY_SET = new Set<string>(SPA_TELEMETRY_FORBIDDEN_KEYS);

const ALLOWED_PARAM_KEYS = new Set<string>([
  'platform',
  'appVersion',
  'assistantOrigin',
  'searchSource',
  'candidateChannel',
  'recommendationPosition',
  'candidateCountBucket',
  'partial',
  'rankingVersion',
  'rankingStatus',
  'quickActionKind',
  'quickActionAvailability',
  'targetKind',
  'originSurface',
  'failureReason',
  'sessionOutcome',
  'timeToChoiceBucket',
  'journeyId',
]);

export function isSpaTelemetryEventName(value: unknown): value is SpaTelemetryEventName {
  return (
    typeof value === 'string' &&
    (SPA_TELEMETRY_EVENT_NAMES as readonly string[]).includes(value)
  );
}

export function bucketCandidateCount(count: number): SpaCountBucket {
  if (!Number.isFinite(count) || count <= 0) return '0';
  if (count === 1) return '1';
  if (count <= 3) return '2_3';
  if (count <= 8) return '4_8';
  return '9_plus';
}

export function bucketLatencyMs(elapsedMs: number): SpaLatencyBucket {
  if (!Number.isFinite(elapsedMs) || elapsedMs < 0) return 'gt_60s';
  if (elapsedMs < 5_000) return 'lt_5s';
  if (elapsedMs < 15_000) return '5_15s';
  if (elapsedMs < 30_000) return '15_30s';
  if (elapsedMs < 60_000) return '30_60s';
  return 'gt_60s';
}

/** Cryptographically weak random journey id — anonymous, ephemeral. */
export function createSpaJourneyId(): string {
  const bytes = new Uint8Array(16);
  if (typeof globalThis.crypto?.getRandomValues === 'function') {
    globalThis.crypto.getRandomValues(bytes);
  } else {
    for (let i = 0; i < bytes.length; i += 1) {
      bytes[i] = Math.floor(Math.random() * 256);
    }
  }
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
}

function normalizeKey(key: string): string {
  return key.trim().toLowerCase();
}

function assertNoForbiddenValue(value: unknown): void {
  if (typeof value === 'string') {
    if (
      value.includes('maps://') ||
      value.includes('geo:') ||
      value.includes('openstreetmap') ||
      /^-?\d+\.\d+,-?\d+\.\d+$/.test(value) ||
      value.includes('@')
    ) {
      throw new Error('Forbidden analytics parameter value');
    }
  }
  if (value && typeof value === 'object') {
    assertSpaTelemetryParams(value as Record<string, unknown>);
  }
}

/**
 * Rejects forbidden keys (including nested objects) and unknown param keys.
 * Throws on violation — callers that must fail-open should catch.
 */
export function assertSpaTelemetryParams(params?: SpaTelemetryParams | null): void {
  if (!params) return;
  for (const [key, value] of Object.entries(params)) {
    const normalized = normalizeKey(key);
    if (FORBIDDEN_KEY_SET.has(normalized)) {
      throw new Error(`Forbidden analytics parameter: ${key}`);
    }
    if (!ALLOWED_PARAM_KEYS.has(key)) {
      throw new Error(`Unknown analytics parameter: ${key}`);
    }
    if (value === undefined) continue;
    assertNoForbiddenValue(value);
  }
}

export function sanitizeSpaTelemetryParams(
  params?: SpaTelemetryParams | null,
): SpaTelemetryParams | undefined {
  if (!params) return undefined;
  assertSpaTelemetryParams(params);
  const out: SpaTelemetryParams = {};
  for (const key of Object.keys(params) as Array<keyof SpaTelemetryParams>) {
    const value = params[key];
    if (value !== undefined) {
      (out as Record<string, unknown>)[key] = value;
    }
  }
  return out;
}
