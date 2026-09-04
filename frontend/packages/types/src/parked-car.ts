/**
 * Canonical parked-car presentation view (WP-SPA-11).
 *
 * Computed from ACTIVE {@link ParkingSessionResponse} — not a persistence contract.
 * ParkingSession remains the source of truth for the active parked state.
 */

import type { ParkingSource, ParkingSessionStatus } from './parking';
import type { RecentParkingTargetKind } from './recent';

/** Client lifecycle over ParkingSession — RETURNING is an action, not stored state. */
export type ParkedCarLifecycle = 'NONE' | 'ACTIVE' | 'ENDING';

/**
 * Optional parking target associated at explicit “Park Here” time.
 * Used for RecentParking recording; not required on every session.
 */
export type ParkedCarTargetKind = RecentParkingTargetKind;

export interface ParkedCarTargetRef {
  kind: ParkedCarTargetKind;
  targetId: string;
  /** Display label known at park time (ephemeral; may be absent after reload). */
  displayLabel?: string | null;
}

/**
 * Presentation model for the active parked car.
 * Coordinates come from ParkingSession; no separate datastore.
 */
export interface ParkedCarView {
  sessionId: string;
  status: Extract<ParkingSessionStatus, 'ACTIVE'>;
  lifecycle: 'ACTIVE';
  parkedAt: string;
  latitude: number;
  longitude: number;
  parkingSource: ParkingSource;
  returnAvailable: boolean;
  /** Optional elapsed minutes when both timestamps are valid. */
  elapsedMinutes: number | null;
  /** Ephemeral target context when the client still knows it. */
  target: ParkedCarTargetRef | null;
  displayLabel: string | null;
}
