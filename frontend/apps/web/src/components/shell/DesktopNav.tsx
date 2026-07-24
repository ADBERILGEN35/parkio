import { Icon, cn } from '@parkio/ui';
import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, NavLink } from 'react-router-dom';
import { useAuthStore } from '@/auth/store';
import { BrandMark } from '@/components/brand/BrandMark';
import { ROUTE_IDS, type RouteId } from '@/routing/route-manifest';
import {
  getPrimaryNav,
  getSecondaryNav,
  getStaffNavItems,
  type NavItem,
} from './navConfig';
import { UnreadBadge } from './UnreadBadge';

function requiredNavigationItem(
  items: readonly NavItem[],
  routeId: RouteId,
): NavItem {
  const item = items.find((candidate) => candidate.id === routeId);
  if (!item) {
    throw new Error(`Missing navigation metadata for route '${routeId}'.`);
  }
  return item;
}

/** Fixed top glass bar — DESIGN_SYSTEM §2.1 TopNavBar (desktop). */
export function DesktopNav() {
  const { t } = useTranslation('navigation');
  const roles = useAuthStore((s) => s.roles);
  const primaryNav = getPrimaryNav(t);
  const secondaryNav = getSecondaryNav(t);
  const staffNav = getStaffNavItems(roles, t);
  const mapItem = requiredNavigationItem(primaryNav, ROUTE_IDS.MAP);
  const uploadItem = requiredNavigationItem(primaryNav, ROUTE_IDS.UPLOAD);
  const profileItem = requiredNavigationItem(primaryNav, ROUTE_IDS.PROFILE);

  return (
    <header className="fixed inset-x-0 top-0 z-50 hidden h-16 border-b border-outline-variant/20 bg-surface/70 shadow-sm backdrop-blur-xl md:block">
      <div className="mx-auto flex h-full max-w-7xl items-center gap-sm px-md">
        <Link
          to={mapItem.to}
          className="mr-sm flex shrink-0 items-center gap-xs text-headline-md font-bold text-primary no-underline"
          aria-label={t('homeAria')}
        >
          <BrandMark size={28} className="select-none" />
          {t('brand')}
        </Link>

        <nav className="flex min-w-0 flex-1 items-center gap-xs overflow-x-auto hide-scrollbar">
          {primaryNav.filter((item) => item.id !== ROUTE_IDS.PROFILE).map((item) => (
            <DesktopNavLink key={item.to} to={item.to}>
              {item.label}
            </DesktopNavLink>
          ))}
          {secondaryNav.map((item) => (
            <DesktopNavLink key={item.to} to={item.to}>
              {item.label}
              {item.id === ROUTE_IDS.NOTIFICATIONS ? <UnreadBadge /> : null}
            </DesktopNavLink>
          ))}
          {staffNav.map((item) => (
            <DesktopNavLink key={item.to} to={item.to}>
              {item.label}
            </DesktopNavLink>
          ))}
        </nav>

        <div className="ml-auto flex shrink-0 items-center gap-sm">
          <Link
            to={uploadItem.to}
            className="inline-flex items-center gap-xs rounded-full bg-primary px-md py-sm text-label-md text-on-primary no-underline shadow-sm transition-all duration-std hover:bg-primary/90 motion-safe:active:scale-95"
          >
            <Icon name={uploadItem.icon} className="text-[16px] leading-none" />
            {t('shareSpot')}
          </Link>
          <DesktopNavLink to={profileItem.to}>
            {profileItem.label}
          </DesktopNavLink>
        </div>
      </div>
    </header>
  );
}

function DesktopNavLink({ to, children }: { to: string; children: ReactNode }) {
  return (
    <NavLink
      to={to}
      className={({ isActive }) =>
        cn(
          'inline-flex items-center whitespace-nowrap px-sm py-sm text-label-md no-underline transition-colors duration-std',
          isActive
            ? 'border-b-2 border-primary font-bold text-primary'
            : 'border-b-2 border-transparent text-on-surface-variant hover:text-on-surface',
        )
      }
    >
      {children}
    </NavLink>
  );
}
