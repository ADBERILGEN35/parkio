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

export const PRIVILEGED_NAV = [
  { to: '/moderation', label: 'Moderation', icon: 'gavel' },
  { to: '/analytics', label: 'Analytics', icon: 'insights' },
] as const;
