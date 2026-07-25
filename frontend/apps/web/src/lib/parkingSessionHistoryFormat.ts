import type { ParkingSessionResponse, ParkingSessionStatus } from '@parkio/types';

/** Terminal statuses the history UI may expose for deletion. */
export const TERMINAL_PARKING_SESSION_STATUSES = ['COMPLETED', 'CANCELLED'] as const;

export type TerminalParkingSessionStatus = (typeof TERMINAL_PARKING_SESSION_STATUSES)[number];

export function isTerminalParkingSessionStatus(
  status: ParkingSessionStatus | string,
): status is TerminalParkingSessionStatus {
  return status === 'COMPLETED' || status === 'CANCELLED';
}

/**
 * Flatten infinite-query pages, drop duplicates, and keep terminal rows only.
 * ACTIVE/unknown statuses are excluded from delete-eligible history display.
 */
export function flattenTerminalHistoryPages(
  pages: ReadonlyArray<{ items: readonly ParkingSessionResponse[] }> | undefined,
): ParkingSessionResponse[] {
  if (!pages) return [];
  const seen = new Set<string>();
  const out: ParkingSessionResponse[] = [];
  for (const page of pages) {
    for (const item of page.items) {
      if (!isTerminalParkingSessionStatus(item.status)) continue;
      if (seen.has(item.id)) continue;
      seen.add(item.id);
      out.push(item);
    }
  }
  return out;
}

/**
 * Duration between startedAt and endedAt for terminal sessions.
 * Never uses wall-clock "now". Invalid/missing/negative → null.
 */
export function terminalSessionDurationMs(
  startedAt: string,
  endedAt: string | null | undefined,
): number | null {
  if (endedAt == null || endedAt === '') return null;
  const started = Date.parse(startedAt);
  const ended = Date.parse(endedAt);
  if (!Number.isFinite(started) || !Number.isFinite(ended)) return null;
  const delta = ended - started;
  if (delta < 0) return null;
  return delta;
}

export type DurationParts = { hours: number; minutes: number };

/** Whole hours + remaining minutes for copy interpolation. Null when unknown. */
export function terminalDurationParts(
  startedAt: string,
  endedAt: string | null | undefined,
): DurationParts | null {
  const ms = terminalSessionDurationMs(startedAt, endedAt);
  if (ms === null) return null;
  const totalMinutes = Math.floor(ms / 60_000);
  return {
    hours: Math.floor(totalMinutes / 60),
    minutes: totalMinutes % 60,
  };
}