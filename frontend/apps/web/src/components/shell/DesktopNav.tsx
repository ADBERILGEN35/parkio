import { hasPrivilegedRole } from '@parkio/types';
import { Icon, cn } from '@parkio/ui';
import type { ReactNode } from 'react';
import { Link, NavLink } from 'react-router-dom';
import { useAuthStore } from '@/auth/store';
import { PRIVILEGED_NAV, PRIMARY_NAV, SECONDARY_NAV } from './navConfig';
import { UnreadBadge } from './UnreadBadge';

/** Fixed top glass bar — DESIGN_SYSTEM §2.1 TopNavBar (desktop). */
export function DesktopNav() {
  const roles = useAuthStore((s) => s.roles);
  const privileged = hasPrivilegedRole(roles);

  return (
    <header className="fixed inset-x-0 top-0 z-50 hidden h-16 border-b border-outline-variant/20 bg-surface/70 shadow-sm backdrop-blur-xl md:block">
      <div className="mx-auto flex h-full max-w-7xl items-center gap-sm px-md">
        <Link
          to="/map"
          className="mr-sm flex shrink-0 items-center gap-xs text-headline-md font-bold text-primary no-underline"
          aria-label="Parkio home"
        >
          <span aria-hidden className="material-symbols-outlined filled select-none">
            local_parking
          </span>
          Parkio
        </Link>

        <nav className="flex min-w-0 flex-1 items-center gap-xs overflow-x-auto hide-scrollbar">
          {PRIMARY_NAV.filter((item) => item.to !== '/profile').map((item) => (
            <DesktopNavLink key={item.to} to={item.to}>
              {item.label}
            </DesktopNavLink>
          ))}
          {SECONDARY_NAV.map((item) => (
            <DesktopNavLink key={item.to} to={item.to}>
              {item.label}
              {item.to === '/notifications' ? <UnreadBadge /> : null}
            </DesktopNavLink>
          ))}
          {privileged
            ? PRIVILEGED_NAV.map((item) => (
                <DesktopNavLink key={item.to} to={item.to}>
                  {item.label}
                </DesktopNavLink>
              ))
            : null}
        </nav>

        <div className="ml-auto flex shrink-0 items-center gap-sm">
          <Link
            to="/upload"
            className="inline-flex items-center gap-xs rounded-full bg-primary px-md py-sm text-label-md text-on-primary no-underline shadow-sm transition-all duration-std hover:bg-primary/90 motion-safe:active:scale-95"
          >
            <Icon name="add_location_alt" className="text-[16px] leading-none" />
            Share a spot
          </Link>
          <DesktopNavLink to="/profile">Profile</DesktopNavLink>
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
