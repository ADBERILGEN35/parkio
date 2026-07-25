import type { ParkingSessionResponse } from '@parkio/types';
import { needsActiveConfirmation as sharedNeedsActiveConfirmation } from '@parkio/validation';

/**
 * True when an ACTIVE session must be confirmed before Find My Car.
 * `confirmAfterMs` comes from GET /parking/sessions/lifecycle-config (single source of truth).
 */
export function needsActiveConfirmation(
  session: Pick<ParkingSessionResponse, 'status' | 'startedAt' | 'lastConfirmedAt'>,
  confirmAfterMs: number,
  nowMs: number = Date.now(),
): boolean {
  return sharedNeedsActiveConfirmation(session, confirmAfterMs, nowMs);
}