import { hasAdminRole, hasPrivilegedRole } from '@parkio/types';

/** Primary destinations — surfaced in the mobile bottom bar (DESIGN_SYSTEM §2.1). */
export const PRIMARY_NAV = [
  { to: '/map', label: 'Map', icon: 'map' },
  { to: '/my-spots', label: 'My spots', icon: 'bookmark' },
  { to: '/upload', label: 'Share', icon: 'add_location_alt' },
  { to: '/leaderboard', label: 'Leaderboard', icon: 'leaderboard' },
  { to: '/profile', label: 'Profile', icon: 'account_circle' },
] as const;

/** Secondary destinations — desktop top bar + mobile overflow menu. */
export const SECONDARY_NAV = [
  { to: '/reports', label: 'My reports', icon: 'flag' },
  { to: '/gamification', label: 'Impact', icon: 'military_tech' },
  { to: '/notifications', label: 'Notifications', icon: 'notifications' },
] as const;

export const MODERATOR_NAV = [{ to: '/admin/moderation', label: 'Moderation', icon: 'gavel' }] as const;

export const ADMIN_NAV = [{ to: '/admin', label: 'Admin', icon: 'admin_panel_settings' }] as const;

/** Staff destinations visible for the caller's roles (moderation vs platform analytics). */
export function getStaffNavItems(roles: string[]) {
  const items = [];
  if (hasPrivilegedRole(roles)) {
    items.push(...MODERATOR_NAV);
  }
  if (hasAdminRole(roles)) {
    items.push(...ADMIN_NAV);
  }
  return items;
}
