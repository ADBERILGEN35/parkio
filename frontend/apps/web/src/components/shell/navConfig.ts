import { hasAdminRole, hasPrivilegedRole } from '@parkio/types';
import type { TFunction } from 'i18next';
import {
  ROUTE_MANIFEST,
  getRouteManifestEntry,
  getRoutePath,
  type RouteId,
  type RouteNavigationGroup,
  type RouteRoleRequirement,
} from '@/routing/route-manifest';

export interface NavItem {
  id: RouteId;
  to: string;
  label: string;
  icon: string;
}

export interface AdminShellNavItem {
  id: RouteId;
  to: string;
  label: string;
  end: boolean;
}

function getNavigationItems(
  group: RouteNavigationGroup,
  t: TFunction,
  role?: RouteRoleRequirement,
): NavItem[] {
  return ROUTE_MANIFEST.filter(
    (entry) =>
      entry.navigation?.group === group &&
      (role === undefined || entry.role === role),
  )
    .sort(
      (left, right) =>
        left.navigation!.order - right.navigation!.order,
    )
    .map((entry) => ({
      id: entry.id,
      to: getRoutePath(entry.id),
      label: t(entry.navigation!.labelKey),
      icon: entry.navigation!.icon,
    }));
}

/** Primary destinations — surfaced in the mobile bottom bar (DESIGN_SYSTEM §2.1). */
export function getPrimaryNav(t: TFunction): NavItem[] {
  return getNavigationItems('primary', t);
}

/** Secondary destinations — desktop top bar + mobile overflow menu. */
export function getSecondaryNav(t: TFunction): NavItem[] {
  return getNavigationItems('secondary', t);
}

export function getModeratorNav(t: TFunction): NavItem[] {
  return getNavigationItems('staff', t, 'privileged');
}

export function getAdminNav(t: TFunction): NavItem[] {
  return getNavigationItems('staff', t, 'admin');
}

/** Staff destinations visible for the caller's roles (moderation vs platform analytics). */
export function getStaffNavItems(roles: string[], t: TFunction): NavItem[] {
  return getNavigationItems('staff', t).filter((item) => {
    const requirement = getRouteManifestEntry(item.id).role;
    if (requirement === 'privileged') {
      return hasPrivilegedRole(roles);
    }
    if (requirement === 'admin') {
      return hasAdminRole(roles);
    }
    return true;
  });
}

/** Administration sidebar destinations projected only from canonical manifest metadata. */
export function getAdminShellNav(t: TFunction): AdminShellNavItem[] {
  return ROUTE_MANIFEST.filter((entry) => entry.shellNavigation)
    .sort(
      (left, right) =>
        left.shellNavigation!.order - right.shellNavigation!.order,
    )
    .map((entry) => ({
      id: entry.id,
      to: getRoutePath(entry.id),
      label: t(entry.shellNavigation!.labelKey),
      end: entry.shellNavigation!.end,
    }));
}
