/**
 * Central status → visual mapping for the Parkio design system.
 *
 * Pure data only (labels, Material Symbols icon names, Tailwind class
 * strings) — no DOM, no backend coupling. The soft-badge recipe is
 * `bg-{color}/10..20 + solid {color} text` per DESIGN_SYSTEM.md §2.6.
 */

/** Mirrors parking-service `ParkingSpotStatus` values (string-compatible with `@parkio/types`). */
export type SpotStatus = 'ACTIVE' | 'VERIFIED' | 'SUSPICIOUS' | 'FILLED' | 'EXPIRED' | 'REJECTED';

export interface StatusVisual {
  /** Human-readable label. */
  label: string;
  /** Material Symbols Outlined icon name. */
  icon: string;
  /** Soft badge classes: tinted background + solid status text. */
  className: string;
  /** Solid accent classes for dots / left border bars. */
  dotClassName: string;
}

const NEUTRAL_STATUS_VISUAL: StatusVisual = {
  label: 'Unknown',
  icon: 'help',
  className: 'bg-on-surface-variant/10 text-on-surface-variant',
  dotClassName: 'bg-on-surface-variant',
};

export const SPOT_STATUS_VISUALS: Record<SpotStatus, StatusVisual> = {
  ACTIVE: {
    label: 'Active',
    icon: 'radio_button_checked',
    className: 'bg-primary/10 text-primary',
    dotClassName: 'bg-primary',
  },
  VERIFIED: {
    label: 'Verified',
    icon: 'verified',
    className: 'bg-secondary/10 text-secondary',
    dotClassName: 'bg-secondary',
  },
  SUSPICIOUS: {
    label: 'Suspicious',
    icon: 'warning',
    className: 'bg-tertiary-container/20 text-tertiary',
    dotClassName: 'bg-tertiary',
  },
  FILLED: {
    label: 'Filled',
    icon: 'block',
    className: 'bg-on-surface-variant/10 text-on-surface-variant',
    dotClassName: 'bg-on-surface-variant',
  },
  EXPIRED: {
    label: 'Expired',
    icon: 'history',
    className: 'bg-error/10 text-error',
    dotClassName: 'bg-error',
  },
  REJECTED: {
    label: 'Rejected',
    icon: 'cancel',
    className: 'bg-error-container text-on-error-container',
    dotClassName: 'bg-on-error-container',
  },
};

/** Tolerant lookup — unknown/future statuses fall back to a neutral visual. */
export function getSpotStatusVisual(status: string): StatusVisual {
  return SPOT_STATUS_VISUALS[status as SpotStatus] ?? NEUTRAL_STATUS_VISUAL;
}

/* ------------------------------------------------------------------------- */
/* Trust freshness — how recently a spot was confirmed.                       */
/* ------------------------------------------------------------------------- */

export type TrustFreshness = 'fresh' | 'recent' | 'aging' | 'stale';

export interface TrustFreshnessVisual {
  /** Human-readable label. */
  label: string;
  /** Material Symbols Outlined icon name. */
  icon: string;
  /** Text/icon color class. */
  className: string;
}

export const TRUST_FRESHNESS_VISUALS: Record<TrustFreshness, TrustFreshnessVisual> = {
  /** 0–10 minutes */
  fresh: { label: 'Fresh', icon: 'verified', className: 'text-secondary' },
  /** 10–30 minutes */
  recent: { label: 'Recent', icon: 'history', className: 'text-primary' },
  /** 30–60 minutes */
  aging: { label: 'Aging', icon: 'schedule', className: 'text-tertiary' },
  /** 1 hour and older */
  stale: { label: 'Stale', icon: 'history_toggle_off', className: 'text-outline' },
};

/** Buckets an age in minutes: 0–10 fresh, 10–30 recent, 30–60 aging, 1h+ stale. */
export function trustFreshnessFromMinutes(minutesAgo: number): TrustFreshness {
  if (minutesAgo < 10) return 'fresh';
  if (minutesAgo < 30) return 'recent';
  if (minutesAgo < 60) return 'aging';
  return 'stale';
}

/** Convenience: bucket + visual from a timestamp (ISO string or Date). */
export function getTrustFreshnessVisual(
  verifiedAt: string | Date,
  now: Date = new Date(),
): TrustFreshnessVisual & { freshness: TrustFreshness } {
  const at = typeof verifiedAt === 'string' ? new Date(verifiedAt) : verifiedAt;
  const minutesAgo = Math.max(0, (now.getTime() - at.getTime()) / 60_000);
  const freshness = trustFreshnessFromMinutes(minutesAgo);
  return { freshness, ...TRUST_FRESHNESS_VISUALS[freshness] };
}
