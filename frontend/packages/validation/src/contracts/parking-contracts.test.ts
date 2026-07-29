import { describe, expect, it } from 'vitest';
import {
  activeParkingSessionFixture,
  cancelledParkingSessionFixture,
  communityParkingSessionFixture,
  communityClaimFixture,
  completedParkingSessionFixture,
  parkingSessionHistoryFixture,
  startParkingSessionWithFeeFixture,
  startParkingSessionWithoutFeeFixture,
} from './contract-fixtures';
import {
  communityClaimResponseSchema,
  parkingSessionHistoryParamsSchema,
  parkingSessionHistoryResponseSchema,
  parkingSessionResponseSchema,
  publicSpotResponseSchema,
  spotRejectionSchema,
  startParkingSessionRequestSchema,
} from './parking';

describe('parking request contracts', () => {
  it('accepts the frozen manual-start fixtures', () => {
    expect(startParkingSessionRequestSchema.parse(startParkingSessionWithoutFeeFixture)).toEqual(
      startParkingSessionWithoutFeeFixture,
    );
    expect(startParkingSessionRequestSchema.parse(startParkingSessionWithFeeFixture)).toEqual(
      startParkingSessionWithFeeFixture,
    );
  });

  it('rejects unknown and server-controlled request properties', () => {
    expect(
      startParkingSessionRequestSchema.safeParse({
        ...startParkingSessionWithoutFeeFixture,
        parkingSource: 'COMMUNITY',
      }).success,
    ).toBe(false);
    expect(
      startParkingSessionRequestSchema.safeParse({
        ...startParkingSessionWithoutFeeFixture,
        userId: '8a56ef7e-69de-4f3c-8fe5-32b83d67f1b4',
      }).success,
    ).toBe(false);
  });

  it('preserves exact decimal-string money', () => {
    expect(
      startParkingSessionRequestSchema.safeParse({
        ...startParkingSessionWithoutFeeFixture,
        estimatedFee: 125.5,
      }).success,
    ).toBe(false);
    expect(
      startParkingSessionRequestSchema.safeParse({
        ...startParkingSessionWithoutFeeFixture,
        estimatedFee: '10000000000.00',
      }).success,
    ).toBe(false);
    expect(
      startParkingSessionRequestSchema.safeParse({
        ...startParkingSessionWithoutFeeFixture,
        estimatedFee: '000125.50',
      }).success,
    ).toBe(true);
  });

  it('validates the bounded opaque history cursor without interpreting it', () => {
    expect(parkingSessionHistoryParamsSchema.safeParse({ size: 1, cursor: 'opaque' }).success).toBe(
      true,
    );
    expect(parkingSessionHistoryParamsSchema.safeParse({ size: 0 }).success).toBe(false);
    expect(parkingSessionHistoryParamsSchema.safeParse({ size: 101 }).success).toBe(false);
    expect(parkingSessionHistoryParamsSchema.safeParse({ cursor: 'x'.repeat(513) }).success).toBe(
      false,
    );
    expect(parkingSessionHistoryParamsSchema.safeParse({ cursor: 'abc=' }).success).toBe(false);
    expect(parkingSessionHistoryParamsSchema.safeParse({ size: 20, extra: true }).success).toBe(
      false,
    );
  });
});

describe('parking response contracts', () => {
  it('accepts all frozen parking-session lifecycle fixtures', () => {
    for (const fixture of [
      activeParkingSessionFixture,
      completedParkingSessionFixture,
      cancelledParkingSessionFixture,
      communityParkingSessionFixture,
    ]) {
      expect(parkingSessionResponseSchema.parse(fixture)).toEqual(fixture);
    }
    expect(parkingSessionHistoryResponseSchema.parse(parkingSessionHistoryFixture)).toEqual(
      parkingSessionHistoryFixture,
    );
    expect(communityClaimResponseSchema.parse(communityClaimFixture)).toEqual(communityClaimFixture);
  });

  it('accepts additive response fields and exposes only the frozen surface', () => {
    const parsed = parkingSessionHistoryResponseSchema.parse({
      ...parkingSessionHistoryFixture,
      futurePageField: 'ignored',
      items: [
        {
          ...completedParkingSessionFixture,
          futureSessionField: 'ignored',
        },
      ],
    });

    expect(parsed).not.toHaveProperty('futurePageField');
    expect(parsed.items[0]).not.toHaveProperty('futureSessionField');
  });

  it('rejects unknown closed-enum members', () => {
    expect(
      parkingSessionResponseSchema.safeParse({
        ...activeParkingSessionFixture,
        status: 'PAUSED',
      }).success,
    ).toBe(false);
    expect(
      parkingSessionResponseSchema.safeParse({
        ...activeParkingSessionFixture,
        parkingSource: 'CLIENT_PROVIDED',
      }).success,
    ).toBe(false);
    expect(
      communityClaimResponseSchema.safeParse({
        ...communityClaimFixture,
        status: 'UNKNOWN_FUTURE_STATUS',
      }).success,
    ).toBe(false);
  });

  it('rejects invalid identifiers, dates, coordinates, and response money', () => {
    expect(
      parkingSessionResponseSchema.safeParse({ ...activeParkingSessionFixture, id: 'not-a-uuid' })
        .success,
    ).toBe(false);
    expect(
      parkingSessionResponseSchema.safeParse({
        ...activeParkingSessionFixture,
        startedAt: '2026-07-21',
      }).success,
    ).toBe(false);
    expect(
      parkingSessionResponseSchema.safeParse({ ...activeParkingSessionFixture, latitude: 91 }).success,
    ).toBe(false);
    expect(
      parkingSessionResponseSchema.safeParse({
        ...activeParkingSessionFixture,
        estimatedFee: '125.5',
  lastConfirmedAt: '2026-07-25T10:00:00.000Z',
  completionType: null,
      }).success,
    ).toBe(false);
  });
});

describe('spot rejection contracts', () => {
  const baseSpot = {
    id: '0b8f6c3a-1111-0000-0000-000000000001',
    mediaId: '0b8f6c3a-1111-0000-0000-000000000002',
    latitude: 41.01,
    longitude: 29.02,
    addressText: null,
    description: null,
    manualLocationEdited: false,
    suitableVehicleTypes: ['SEDAN'] as const,
    parkingContext: 'STREET_PARKING' as const,
    legalStatus: 'LEGAL' as const,
    violationReasons: [] as const,
    status: 'REJECTED' as const,
    expiresAt: null,
    createdAt: '2026-07-29T12:00:00.000Z',
    updatedAt: '2026-07-29T12:00:00.000Z',
  };

  it('parses legacy payloads without rejection', () => {
    expect(publicSpotResponseSchema.safeParse(baseSpot).success).toBe(true);
  });

  it('parses rejection with code but no message', () => {
    expect(
      spotRejectionSchema.safeParse({
        code: 'CLEARLY_UNRELATED_CONTENT',
        source: 'AI_POLICY',
        rejectedAt: '2026-07-29T12:00:00.000Z',
        rejectedBy: null,
        policyVersion: '2026-07-photo-policy-v3-recall',
      }).success,
    ).toBe(true);
  });

  it('parses rejection with message and unknown code', () => {
    expect(
      spotRejectionSchema.safeParse({
        code: 'FUTURE_CODE',
        message: 'Compat text',
        source: 'AI_POLICY',
        rejectedAt: '2026-07-29T12:00:00.000Z',
        rejectedBy: null,
        policyVersion: null,
        moderatorNote: null,
      }).success,
    ).toBe(true);
  });
});
