import { formatCountdown } from '@/lib/time';

/**
 * Elapsed ms since startedAt, clamped to >= 0.
 * Returns null when startedAt cannot be parsed (caller should fail closed).
 */
export function elapsedMsFromStartedAt(startedAt: string, now: number): number | null {
  const started = Date.parse(startedAt);
  if (!Number.isFinite(started)) {
    return null;
  }
  return Math.max(0, now - started);
}

/**
 * Safe tabular elapsed display (mm:ss or h:mm:ss) derived from startedAt + now.
 * Invalid/future startedAt never yields NaN, negatives, or raw ISO strings.
 */
export function formatElapsedFromStartedAt(startedAt: string, now: number): string {
  const elapsed = elapsedMsFromStartedAt(startedAt, now);
  if (elapsed === null) {
    return formatCountdown(0);
  }
  return formatCountdown(elapsed);
}