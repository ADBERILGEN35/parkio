import { describe, expect, it } from 'vitest';
import i18n from 'i18next';
import {
  ROUTE_MANIFEST,
  getRoutePath,
  type RouteNavigationGroup,
} from '@/routing/route-manifest';
import {
  getAdminShellNav,
  getPrimaryNav,
  getSecondaryNav,
  getStaffNavItems,
} from './navConfig';

function expectedNavigation(group: RouteNavigationGroup) {
  return ROUTE_MANIFEST.filter(
    (entry) => entry.navigation?.group === group,
  )
    .sort(
      (left, right) =>
        left.navigation!.order - right.navigation!.order,
    )
    .map((entry) => ({
      id: entry.id,
      to: getRoutePath(entry.id),
      label: i18n.t(entry.navigation!.labelKey),
      icon: entry.navigation!.icon,
    }));
}

describe('manifest-derived navigation', () => {
  const t = i18n.t.bind(i18n);

  it('projects primary and secondary membership, destinations, labels, icons, and order from the manifest', () => {
    expect(getPrimaryNav(t)).toEqual(expectedNavigation('primary'));
    expect(getSecondaryNav(t)).toEqual(expectedNavigation('secondary'));
  });

  it('returns no staff links for ordinary users', () => {
    expect(getStaffNavItems(['USER'], t)).toEqual([]);
  });

  it('returns moderation only for moderators', () => {
    expect(getStaffNavItems(['MODERATOR'], t).map((item) => item.to)).toEqual(['/admin/moderation']);
  });

  it('returns moderation and admin for admins', () => {
    expect(getStaffNavItems(['ADMIN'], t)).toEqual(
      expectedNavigation('staff'),
    );
  });

  it('projects the administration sidebar from manifest metadata', () => {
    expect(getAdminShellNav(t)).toEqual(
      ROUTE_MANIFEST.filter((entry) => entry.shellNavigation)
        .sort(
          (left, right) =>
            left.shellNavigation!.order - right.shellNavigation!.order,
        )
        .map((entry) => ({
          id: entry.id,
          to: getRoutePath(entry.id),
          label: i18n.t(entry.shellNavigation!.labelKey),
          end: entry.shellNavigation!.end,
        })),
    );
  });
});
