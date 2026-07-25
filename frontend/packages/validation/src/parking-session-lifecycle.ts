import type { ParkingSessionResponse } from '@parkio/types';

function confirmationAnchorMs(
  session: Pick<ParkingSessionResponse, 'startedAt' | 'lastConfirmedAt'>,
): number {
  const raw = session.lastConfirmedAt ?? session.startedAt;
  const ms = Date.parse(raw);
  return Number.isFinite(ms) ? ms : Date.parse(session.startedAt);
}

/**
 * True when an ACTIVE session has not been confirmed within the configured
 * confirm-after window. Thresholds come from GET /parking/sessions/lifecycle-config.
 */
export function needsActiveConfirmation(
  session: Pick<ParkingSessionResponse, 'status' | 'startedAt' | 'lastConfirmedAt'>,
  confirmAfterMs: number,
  nowMs: number = Date.now(),
): boolean {
  if (session.status !== 'ACTIVE') return false;
  if (!Number.isFinite(confirmAfterMs) || confirmAfterMs <= 0) return false;
  const anchor = confirmationAnchorMs(session);
  if (!Number.isFinite(anchor)) return false;
  return nowMs - anchor >= confirmAfterMs;
}