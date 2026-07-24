import type { ParkingSessionResponse, ParkingSessionStatus } from '@parkio/types';
import { formatCountdown, formatDate, formatShortDuration } from '@/lib/time';

/** Terminal statuses the history UI may expose for deletion. */
export const TERMINAL_PARKING_SESSION_STATUSES = ['COMPLETED', 'CANCELLED'] as const;

export type TerminalParkingSessionStatus = (typeof TERMINAL_PARKING_SESSION_STATUSES)[number];

export function isTerminalParkingSessionStatus(
  status: ParkingSessionStatus | string,
): status is TerminalParkingSessionStatus {
  return status === 'COMPLETED' || status === 'CANCELLED';
}

/**
 * Defensive filter: history UI must never treat ACTIVE (or unknown) as deletable.
 * Backend contract is terminal-only; this is a client safety net.
 */
export function filterTerminalHistoryItems(
  items: readonly ParkingSessionResponse[],
): ParkingSessionResponse[] {
  const seen = new Set<string>();
  const out: ParkingSessionResponse[] = [];
  for (const item of items) {
    if (!isTerminalParkingSessionStatus(item.status)) {
      continue;
    }
    if (seen.has(item.id)) {
      continue;
    }
    seen.add(item.id);
    out.push(item);
  }
  return out;
}

/**
 * Duration between backend startedAt and endedAt for terminal sessions.
 * Never uses "now". Invalid/missing/negative → null (caller fail-closed).
 */
export function terminalSessionDurationMs(
  startedAt: string,
  endedAt: string | null | undefined,
): number | null {
  if (endedAt == null) {
    return null;
  }
  const started = Date.parse(startedAt);
  const ended = Date.parse(endedAt);
  if (!Number.isFinite(started) || !Number.isFinite(ended)) {
    return null;
  }
  const delta = ended - started;
  if (delta < 0) {
    return null;
  }
  return delta;
}

export function formatTerminalSessionDuration(
  startedAt: string,
  endedAt: string | null | undefined,
  locale: 'tr' | 'en',
): string {
  const ms = terminalSessionDurationMs(startedAt, endedAt);
  if (ms === null) {
    return '';
  }
  // Prefer short human labels for multi-hour sessions; countdown for sub-hour.
  if (ms >= 3_600_000) {
    return formatShortDuration(ms, locale);
  }
  return formatCountdown(ms);
}

export function formatTerminalSessionStarted(
  startedAt: string,
  locale: 'tr' | 'en',
): string {
  return formatDate(startedAt, locale);
}
