import type { MaterialCommunityIcons } from '@expo/vector-icons';
import type { ParkingStatus } from '@parkio/types';
import type { Theme } from '@/theme/tokens';

type IconName = keyof typeof MaterialCommunityIcons.glyphMap;

export interface StatusVisual {
  icon: IconName;
  /** Foreground (icon + label). */
  fg: string;
  /** Soft badge tint (10% fills per the pen conventions). */
  bg: string;
}

/**
 * Centralized status → icon/color mapping (brief §7.3). Status is never
 * color-only: every use pairs the icon with the localized label
 * (`t('status.<STATUS>')`).
 */
export function statusVisual(status: ParkingStatus, theme: Theme): StatusVisual {
  const dark = theme.mode === 'dark';
  const c = theme.colors;
  switch (status) {
    case 'ACTIVE':
      return { icon: 'check-circle-outline', fg: c.primary, bg: dark ? '#0066FF29' : '#0050CB1A' };
    case 'VERIFIED':
      return { icon: 'check-decagram-outline', fg: c.secondary, bg: dark ? '#0F2A1F' : '#006C491A' };
    case 'PENDING_REVIEW':
      return { icon: 'timer-sand', fg: c.tertiary, bg: dark ? '#2A1F0A' : '#7F4F001A' };
    case 'SUSPICIOUS':
      return { icon: 'alert-outline', fg: c.tertiary, bg: dark ? '#2A1F0A' : '#7F4F001A' };
    case 'PENDING_VALIDATION':
      return {
        icon: 'timer-sand-empty',
        fg: c.onSurfaceVariant,
        bg: dark ? '#FFFFFF14' : '#4246561A',
      };
    case 'FILLED':
      return {
        icon: 'cancel',
        fg: c.onSurfaceVariant,
        bg: dark ? '#FFFFFF14' : '#E5EEFF',
      };
    case 'EXPIRED':
      return { icon: 'timer-off-outline', fg: c.error, bg: dark ? '#3A0E0C' : '#BA1A1A1A' };
    case 'REJECTED':
      return { icon: 'close-circle-outline', fg: c.error, bg: dark ? '#3A0E0C' : '#BA1A1A1A' };
    case 'REVIEW_FAILED':
      // Distinct from REJECTED: the platform failed to review, the owner did nothing wrong.
      return { icon: 'alert-circle-outline', fg: c.error, bg: dark ? '#3A0E0C' : '#BA1A1A1A' };
  }
}

/** Marker/ring tone for map + cards; live statuses track freshness separately. */
export function isLiveStatus(status: ParkingStatus): boolean {
  return status === 'ACTIVE' || status === 'VERIFIED';
}
