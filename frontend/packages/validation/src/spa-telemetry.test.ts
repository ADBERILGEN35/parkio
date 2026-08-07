import { describe, expect, it } from 'vitest';
import {
  assertSpaTelemetryParams,
  bucketCandidateCount,
  bucketLatencyMs,
  createSpaJourneyId,
  isSpaTelemetryEventName,
  sanitizeSpaTelemetryParams,
} from './spa-telemetry';

describe('spa-telemetry contracts', () => {
  it('accepts known event names and rejects unknown', () => {
    expect(isSpaTelemetryEventName('destination_confirmed')).toBe(true);
    expect(isSpaTelemetryEventName('assistant_rendered')).toBe(false);
  });

  it('buckets counts and latency safely', () => {
    expect(bucketCandidateCount(0)).toBe('0');
    expect(bucketCandidateCount(1)).toBe('1');
    expect(bucketCandidateCount(3)).toBe('2_3');
    expect(bucketCandidateCount(8)).toBe('4_8');
    expect(bucketCandidateCount(12)).toBe('9_plus');
    expect(bucketLatencyMs(1200)).toBe('lt_5s');
    expect(bucketLatencyMs(12_000)).toBe('5_15s');
    expect(bucketLatencyMs(45_000)).toBe('30_60s');
    expect(bucketLatencyMs(90_000)).toBe('gt_60s');
  });

  it('creates anonymous journey ids', () => {
    const a = createSpaJourneyId();
    const b = createSpaJourneyId();
    expect(a).toMatch(/^[0-9a-f]{32}$/);
    expect(a).not.toBe(b);
  });

  it('accepts allowed payload fields', () => {
    expect(() =>
      sanitizeSpaTelemetryParams({
        platform: 'web',
        assistantOrigin: 'SEARCH',
        candidateChannel: 'MUNICIPAL_FACILITY',
        candidateCountBucket: '2_3',
        partial: true,
        rankingStatus: 'APPLIED',
        quickActionKind: 'HOME',
        journeyId: createSpaJourneyId(),
      }),
    ).not.toThrow();
  });

  it('rejects forbidden identity and location fields', () => {
    for (const key of [
      'userId',
      'latitude',
      'longitude',
      'label',
      'address',
      'facilityId',
      'spotId',
      'sessionId',
      'targetId',
      'searchQuery',
    ]) {
      expect(() =>
        assertSpaTelemetryParams({ [key]: 'x' } as never),
      ).toThrow(/Forbidden|Unknown/);
    }
  });

  it('rejects nested forbidden keys under allowed fields', () => {
    expect(() =>
      assertSpaTelemetryParams({
        platform: { latitude: 1 } as never,
      }),
    ).toThrow(/Forbidden/);
  });

  it('rejects unknown top-level keys', () => {
    expect(() =>
      assertSpaTelemetryParams({ weirdField: true } as never),
    ).toThrow(/Unknown/);
  });
});
