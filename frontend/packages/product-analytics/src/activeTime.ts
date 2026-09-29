import type { AnalyticsScreenName } from '@parkio/types';
import type { ScreenActiveTimeSnapshot } from './types';

export interface ActiveTimeOptions {
  idleTimeoutMs?: number;
  /** 0 = disabled (preferred). */
  heartbeatIntervalMs?: number;
  monotonicNow?: () => number;
  onHeartbeat?: (snapshot: ScreenActiveTimeSnapshot) => void;
}

/**
 * Monotonic active-time accumulator.
 *
 * - Background / unfocused / idle intervals do not accumulate.
 * - Screen transitions do not double-count (one screen at a time).
 * - Heartbeat is optional; default off — prefer screen_engagement_summary on exit.
 * - Abrupt end without exit → incomplete=true on next summary attempt.
 */
export class ActiveTimeTracker {
  private readonly idleTimeoutMs: number;
  private readonly heartbeatIntervalMs: number;
  private readonly monotonicNow: () => number;
  private readonly onHeartbeat?: (snapshot: ScreenActiveTimeSnapshot) => void;

  private screenName: AnalyticsScreenName | null = null;
  private accumulatedMs = 0;
  private segmentStartedAt: number | null = null;
  private lastInteractionAt: number | null = null;
  private foreground = false;
  private focused = false;
  private incomplete = false;
  private checkpointSeq = 0;
  private lastCheckpointAccumulated = 0;
  private heartbeatTimer: ReturnType<typeof setInterval> | null = null;

  constructor(options: ActiveTimeOptions = {}) {
    this.idleTimeoutMs = options.idleTimeoutMs ?? 60_000;
    this.heartbeatIntervalMs = options.heartbeatIntervalMs ?? 0;
    this.monotonicNow =
      options.monotonicNow ??
      (() =>
        typeof performance !== 'undefined' && typeof performance.now === 'function'
          ? performance.now()
          : Date.now());
    this.onHeartbeat = options.onHeartbeat;
  }

  /** Currently tracked screen, or null when none. */
  getCurrentScreen(): AnalyticsScreenName | null {
    return this.screenName;
  }

  /**
   * Enter a screen. Same-screen re-entry is a no-op (StrictMode remount /
   * redundant effects) — does not restart engagement or imply a new visit.
   * Returns the previous screen snapshot when transitioning away.
   */
  enterScreen(screenName: AnalyticsScreenName): ScreenActiveTimeSnapshot | null {
    if (this.screenName === screenName) {
      return null;
    }
    const previous = this.exitScreen({ incomplete: false });
    this.screenName = screenName;
    this.accumulatedMs = 0;
    this.incomplete = false;
    this.checkpointSeq = 0;
    this.lastCheckpointAccumulated = 0;
    this.lastInteractionAt = this.monotonicNow();
    this.maybeStartSegment();
    this.ensureHeartbeat();
    return previous;
  }

  exitScreen(options?: { incomplete?: boolean }): ScreenActiveTimeSnapshot | null {
    this.closeSegment();
    if (!this.screenName) return null;
    const snapshot: ScreenActiveTimeSnapshot = {
      screenName: this.screenName,
      activeDurationMs: Math.max(0, Math.round(this.accumulatedMs)),
      incomplete: options?.incomplete === true || this.incomplete,
      checkpointSeq: this.checkpointSeq,
    };
    this.screenName = null;
    this.accumulatedMs = 0;
    this.stopHeartbeat();
    return snapshot;
  }

  setForeground(active: boolean): void {
    if (active === this.foreground) return;
    if (!active) this.closeSegment();
    this.foreground = active;
    if (active) {
      this.lastInteractionAt = this.monotonicNow();
      this.maybeStartSegment();
    }
  }

  setFocused(focused: boolean): void {
    if (focused === this.focused) return;
    if (!focused) this.closeSegment();
    this.focused = focused;
    if (focused) {
      this.lastInteractionAt = this.monotonicNow();
      this.maybeStartSegment();
    }
  }

  /** Pointer / key / touch activity — resets idle clock. */
  noteInteraction(): void {
    const now = this.monotonicNow();
    if (this.segmentStartedAt != null && this.lastInteractionAt != null) {
      const idleGap = now - this.lastInteractionAt;
      if (idleGap > this.idleTimeoutMs) {
        // Idle window already closed — restart fresh after interaction.
        this.closeSegment();
      }
    }
    this.lastInteractionAt = now;
    this.maybeStartSegment();
  }

  markIncomplete(): void {
    this.incomplete = true;
  }

  /**
   * Apply a deferred checkpoint that arrived out of order.
   * Only increases accumulated time when seq is newer and delta is positive.
   */
  applyCheckpoint(seq: number, activeDurationMs: number): void {
    if (seq <= this.checkpointSeq) return;
    this.closeSegment();
    const bounded = Math.max(0, Math.round(activeDurationMs));
    // Never inflate above a newer absolute checkpoint; never decrease either.
    if (bounded > this.accumulatedMs) {
      this.accumulatedMs = bounded;
      this.lastCheckpointAccumulated = bounded;
    }
    this.checkpointSeq = seq;
    this.maybeStartSegment();
  }

  snapshot(): ScreenActiveTimeSnapshot | null {
    this.closeSegment();
    this.maybeStartSegment();
    if (!this.screenName) return null;
    return {
      screenName: this.screenName,
      activeDurationMs: Math.max(0, Math.round(this.accumulatedMs)),
      incomplete: this.incomplete,
      checkpointSeq: this.checkpointSeq,
    };
  }

  dispose(): void {
    this.stopHeartbeat();
    this.closeSegment();
    this.screenName = null;
  }

  private maybeStartSegment(): void {
    if (!this.screenName || !this.foreground || !this.focused) return;
    if (this.segmentStartedAt != null) return;
    const now = this.monotonicNow();
    if (
      this.lastInteractionAt != null &&
      now - this.lastInteractionAt > this.idleTimeoutMs
    ) {
      return;
    }
    this.segmentStartedAt = now;
  }

  private closeSegment(): void {
    if (this.segmentStartedAt == null) return;
    const now = this.monotonicNow();
    let end = now;
    if (this.lastInteractionAt != null) {
      const idleCutoff = this.lastInteractionAt + this.idleTimeoutMs;
      if (now > idleCutoff) {
        end = idleCutoff;
      }
    }
    const delta = Math.max(0, end - this.segmentStartedAt);
    this.accumulatedMs += delta;
    this.segmentStartedAt = null;
  }

  private ensureHeartbeat(): void {
    this.stopHeartbeat();
    if (this.heartbeatIntervalMs <= 0 || !this.onHeartbeat) return;
    this.heartbeatTimer = setInterval(() => {
      const snap = this.snapshot();
      if (!snap) return;
      if (snap.activeDurationMs <= this.lastCheckpointAccumulated) return;
      this.checkpointSeq += 1;
      this.lastCheckpointAccumulated = snap.activeDurationMs;
      this.onHeartbeat?.({ ...snap, checkpointSeq: this.checkpointSeq });
    }, this.heartbeatIntervalMs);
  }

  private stopHeartbeat(): void {
    if (this.heartbeatTimer != null) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
  }
}
