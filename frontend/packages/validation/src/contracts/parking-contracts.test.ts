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
      }).success,
    ).toBe(false);
  });
});
