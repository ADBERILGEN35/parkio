import type { LegalStatus, ParkingStatus, PublicSpot } from '@parkio/types';

/**
 * Status → presentation mappings for the discovery UI, shared by web and mobile.
 *
 * IMPORTANT — no fabricated data. Availability and the confidence *tier* are
 * deterministic projections of the real `status` the backend already assigns
 * (`ParkingSpotStatus`). There is no invented number, ETA, or probability:
 * `confidence` is the qualitative weight of the spot's own lifecycle state
 * (a VERIFIED spot is, by definition, more trustworthy than a SUSPICIOUS one).
 */

/** Whether a parking spot can currently be used, derived from `status`. */
export type Availability = 'available' | 'filled' | 'expired' | 'unverified' | 'rejected';

/** Qualitative trust tier derived from `status` (never a fabricated score). */
export type ConfidenceTier = 'high' | 'medium' | 'low' | 'none';

export interface SpotPresentation {
  availability: Availability;
  availabilityLabel: string;
  /** Semantic tone for theming (maps to success/warning/danger/muted palettes). */
  tone: 'success' | 'warning' | 'danger' | 'muted';
  confidence: ConfidenceTier;
  confidenceLabel: string;
  statusLabel: string;
  legalLabel: string;
}

const STATUS_LABELS: Record<ParkingStatus, string> = {
  ACTIVE: 'Active',
  VERIFIED: 'Verified',
  SUSPICIOUS: 'Unconfirmed',
  FILLED: 'Filled',
  EXPIRED: 'Expired',
  REJECTED: 'Rejected',
};

const LEGAL_LABELS: Record<LegalStatus, string> = {
  LEGAL: 'Legal parking',
  UNCERTAIN: 'Legality uncertain',
  ILLEGAL_OR_RISKY: 'Risky / may be illegal',
};

const AVAILABILITY_BY_STATUS: Record<ParkingStatus, Availability> = {
  ACTIVE: 'available',
  VERIFIED: 'available',
  SUSPICIOUS: 'unverified',
  FILLED: 'filled',
  EXPIRED: 'expired',
  REJECTED: 'rejected',
};

const AVAILABILITY_LABELS: Record<Availability, string> = {
  available: 'Likely available',
  filled: 'Reported filled',
  expired: 'Listing expired',
  unverified: 'Not yet confirmed',
  rejected: 'Removed',
};

const TONE_BY_AVAILABILITY: Record<Availability, SpotPresentation['tone']> = {
  available: 'success',
  unverified: 'warning',
  filled: 'danger',
  expired: 'muted',
  rejected: 'muted',
};

const CONFIDENCE_BY_STATUS: Record<ParkingStatus, ConfidenceTier> = {
  VERIFIED: 'high',
  ACTIVE: 'medium',
  SUSPICIOUS: 'low',
  FILLED: 'none',
  EXPIRED: 'none',
  REJECTED: 'none',
};

const CONFIDENCE_LABELS: Record<ConfidenceTier, string> = {
  high: 'High confidence',
  medium: 'Community reported',
  low: 'Low confidence',
  none: 'No confidence',
};

/** Project a spot's real lifecycle fields into display-ready presentation. */
export function presentSpot(spot: Pick<PublicSpot, 'status' | 'legalStatus'>): SpotPresentation {
  const availability = AVAILABILITY_BY_STATUS[spot.status];
  const confidence = CONFIDENCE_BY_STATUS[spot.status];
  return {
    availability,
    availabilityLabel: AVAILABILITY_LABELS[availability],
    tone: TONE_BY_AVAILABILITY[availability],
    confidence,
    confidenceLabel: CONFIDENCE_LABELS[confidence],
    statusLabel: STATUS_LABELS[spot.status],
    legalLabel: LEGAL_LABELS[spot.legalStatus],
  };
}

/** True when a spot is worth surfacing as a usable result (not filled/expired/rejected). */
export function isUsableSpot(spot: Pick<PublicSpot, 'status'>): boolean {
  const a = AVAILABILITY_BY_STATUS[spot.status];
  return a === 'available' || a === 'unverified';
}
