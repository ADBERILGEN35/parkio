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
 * Tone for a trust-band badge. The four known backend `TrustBand` values map
 * explicitly (the old regex heuristic coloured LOW_TRUST/UNTRUSTED green because
 * they contain "TRUST"); unknown/future bands fall back to a heuristic and
 * otherwise render neutral with the label verbatim.
 */
export function trustBandTone(band?: string | null): BadgeTone {
  const value = (band ?? '').toUpperCase();
  switch (value) {
    case 'HIGH_TRUST':
      return 'success';
    case 'MEDIUM_TRUST':
      return 'primary';
    case 'LOW_TRUST':
      return 'warning';
    case 'UNTRUSTED':
      return 'danger';
    default:
      break;
  }
  if (/HIGH|GOLD|VERIFIED|EXCELLENT/.test(value)) return 'success';
  if (/LOW|RISK|FLAG|POOR/.test(value)) return 'danger';
  if (/NEW|MEDIUM|MODERATE/.test(value)) return 'primary';
  return 'neutral';
}
