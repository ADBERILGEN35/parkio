import { hasAdminRole, hasPrivilegedRole } from '@parkio/types';
import type { TFunction } from 'i18next';

export interface NavItem {
  to: string;
  label: string;
  icon: string;
}

/** Primary destinations — surfaced in the mobile bottom bar (DESIGN_SYSTEM §2.1). */
export function getPrimaryNav(t: TFunction): NavItem[] {
  return [
    { to: '/map', label: t('navigation:primary.map'), icon: 'map' },
    { to: '/my-spots', label: t('navigation:primary.mySpots'), icon: 'bookmark' },
    { to: '/upload', label: t('navigation:primary.share'), icon: 'add_location_alt' },
    { to: '/leaderboard', label: t('navigation:primary.leaderboard'), icon: 'leaderboard' },
    { to: '/profile', label: t('navigation:primary.profile'), icon: 'account_circle' },
  ];
}

/** Secondary destinations — desktop top bar + mobile overflow menu. */
export function getSecondaryNav(t: TFunction): NavItem[] {
  return [
    { to: '/reports', label: t('navigation:secondary.reports'), icon: 'flag' },
    { to: '/gamification', label: t('navigation:secondary.impact'), icon: 'military_tech' },
    { to: '/notifications', label: t('navigation:secondary.notifications'), icon: 'notifications' },
  ];
}

export function getModeratorNav(t: TFunction): NavItem[] {
  return [{ to: '/admin/moderation', label: t('navigation:staff.moderation'), icon: 'gavel' }];
}

export function getAdminNav(t: TFunction): NavItem[] {
  return [{ to: '/admin', label: t('navigation:staff.admin'), icon: 'admin_panel_settings' }];
}

/** Staff destinations visible for the caller's roles (moderation vs platform analytics). */
export function getStaffNavItems(roles: string[], t: TFunction): NavItem[] {
  const items: NavItem[] = [];
  if (hasPrivilegedRole(roles)) {
    items.push(...getModeratorNav(t));
  }
  if (hasAdminRole(roles)) {
    items.push(...getAdminNav(t));
  }
  return items;
}
