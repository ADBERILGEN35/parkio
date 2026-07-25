import {
  ConflictError,
  NetworkError,
  NotFoundError,
  TimeoutError,
  ValidationError,
} from '@parkio/api-client';
import { describe, expect, it } from 'vitest';
import {
  ACTIVE_PARKING_SESSION_EXISTS,
  PARKING_SESSION_NOT_ACTIVE,
  PARKING_SESSION_NOT_FOUND,
  isActiveParkingSessionConflict,
  isAmbiguousParkingTransport,
  isStaleParkingSessionConflict,
} from './sessionErrors';

function apiError(code: string, status: number) {
  const body = {
    code,
    message: code,
    traceId: 'trace-1',
    timestamp: '2026-07-25T10:00:00.000Z',
  };
  if (status === 409) {
    return new ConflictError(body);
  }
  return new NotFoundError(body);
}

describe('ParkingSession error classifiers', () => {
  it('detects the active-session conflict by stable code', () => {
    expect(isActiveParkingSessionConflict(apiError(ACTIVE_PARKING_SESSION_EXISTS, 409))).toBe(true);
    expect(isActiveParkingSessionConflict(apiError('SOMETHING_ELSE', 409))).toBe(false);
    expect(isActiveParkingSessionConflict(new Error('boom'))).toBe(false);
  });

  it('detects stale terminal conflicts (not-active or not-found)', () => {
    expect(isStaleParkingSessionConflict(apiError(PARKING_SESSION_NOT_ACTIVE, 409))).toBe(true);
    expect(isStaleParkingSessionConflict(apiError(PARKING_SESSION_NOT_FOUND, 404))).toBe(true);
    expect(isStaleParkingSessionConflict(apiError('DUPLICATE_REPORT', 409))).toBe(false);
  });

  it('does not classify precise validation rejections as retryable transport', () => {
    const validation = new ValidationError(422, {
      code: 'VALIDATION_ERROR',
      message: 'invalid',
      traceId: 'trace-2',
      timestamp: '2026-07-25T10:00:00.000Z',
    });
    expect(isAmbiguousParkingTransport(validation)).toBe(false);
    expect(isActiveParkingSessionConflict(validation)).toBe(false);
    expect(isStaleParkingSessionConflict(validation)).toBe(false);
  });

  it('classifies network and timeout failures as ambiguous transport', () => {
    expect(isAmbiguousParkingTransport(new NetworkError())).toBe(true);
    expect(isAmbiguousParkingTransport(new TimeoutError())).toBe(true);
    expect(isAmbiguousParkingTransport(new Error('nope'))).toBe(false);
  });
});
