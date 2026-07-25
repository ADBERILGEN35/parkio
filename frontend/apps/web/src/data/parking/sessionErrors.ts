import { NetworkError, TimeoutError, isParkioApiError } from '@parkio/api-client';

/**
 * Stable ParkingSession domain error codes and transport classifiers.
 * Owned by the data layer so start/leave flows (later PRs) and tests share one
 * source of truth instead of re-deriving conflict semantics per component.
 */

/** The user already has an ACTIVE session — start must reconcile, not fail. */
export const ACTIVE_PARKING_SESSION_EXISTS = 'ACTIVE_PARKING_SESSION_EXISTS';
/** Terminal transition targeted a session that is no longer ACTIVE. */
export const PARKING_SESSION_NOT_ACTIVE = 'PARKING_SESSION_NOT_ACTIVE';
/** Terminal transition targeted a session that no longer exists. */
export const PARKING_SESSION_NOT_FOUND = 'PARKING_SESSION_NOT_FOUND';

export function isActiveParkingSessionConflict(error: unknown): boolean {
  return isParkioApiError(error) && error.code === ACTIVE_PARKING_SESSION_EXISTS;
}

export function isStaleParkingSessionConflict(error: unknown): boolean {
  return (
    isParkioApiError(error) &&
    (error.code === PARKING_SESSION_NOT_ACTIVE || error.code === PARKING_SESSION_NOT_FOUND)
  );
}

/** Indeterminate transport outcome — the request may or may not have committed. */
export function isAmbiguousParkingTransport(error: unknown): boolean {
  return error instanceof NetworkError || error instanceof TimeoutError;
}
