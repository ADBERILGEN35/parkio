/**
 * Elapsed display helpers for Parking Session UI.
 * Invalid/future startedAt never yields NaN, negatives, or raw ISO strings.
 */

/** mm:ss (or h:mm:ss above an hour) — tabular, zero-padded. */
export function formatCountdown(remainingMs: number): string {
  const total = Math.max(0, Math.floor(remainingMs / 1000));
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const seconds = total % 60;
  const mm = String(minutes).padStart(2, '0');
  const ss = String(seconds).padStart(2, '0');
  return hours > 0 ? `${hours}:${mm}:${ss}` : `${mm}:${ss}`;
}

/** Elapsed ms since startedAt, clamped to >= 0. Null when unparseable. */
export function elapsedMsFromStartedAt(startedAt: string, now: number): number | null {
  const started = Date.parse(startedAt);
  if (!Number.isFinite(started)) {
    return null;
  }
  return Math.max(0, now - started);
}

/** Safe tabular elapsed display derived from startedAt + now. */
export function formatElapsedFromStartedAt(startedAt: string, now: number): string {
  const elapsed = elapsedMsFromStartedAt(startedAt, now);
  if (elapsed === null) {
    return formatCountdown(0);
  }
  return formatCountdown(elapsed);
}