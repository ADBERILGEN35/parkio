import type { BadgeTone } from '@parkio/ui';

/**
 * Tone for an account status badge. Tolerant of unknown/future statuses
 * (falls back to neutral). Mirrors the auth-store `AuthAccountStatus` values.
 */
export function accountStatusTone(status?: string | null): BadgeTone {
  switch ((status ?? '').toUpperCase()) {
    case 'ACTIVE':
      return 'success';
    case 'SUSPENDED':
    case 'BANNED':
      return 'danger';
    case 'PENDING':
      return 'warning';
    default:
      return 'neutral';
  }
}

/**
 * Tone for a trust-band badge. The backend band is an opaque string; we only
 * derive a colour heuristically and otherwise show the label verbatim — no
 * invented thresholds or numeric meaning.
 */
export function trustBandTone(band?: string | null): BadgeTone {
  const value = (band ?? '').toUpperCase();
  if (/TRUST|HIGH|GOLD|VERIFIED|EXCELLENT/.test(value)) return 'success';
  if (/LOW|RISK|FLAG|POOR/.test(value)) return 'danger';
  if (/NEW|MEDIUM|MODERATE/.test(value)) return 'primary';
  return 'neutral';
}
