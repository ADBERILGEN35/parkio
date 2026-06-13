import { hasPrivilegedRole } from '@parkio/types';
import { Icon, cn } from '@parkio/ui';
import { useState, type ReactNode } from 'react';
import { Link, NavLink } from 'react-router-dom';
import { useAuthStore } from '@/auth/store';
import { PRIVILEGED_NAV, PRIMARY_NAV, SECONDARY_NAV } from './navConfig';
import { UnreadBadge } from './UnreadBadge';

/** Fixed bottom tab bar — DESIGN_SYSTEM §2.1 BottomNavBar (mobile). */
export function MobileNav() {
  const roles = useAuthStore((s) => s.roles);
  const privileged = hasPrivilegedRole(roles);
  const [moreOpen, setMoreOpen] = useState(false);

  return (
    <>
      {moreOpen ? (
        <button
          type="button"
          aria-label="Close menu"
          className="fixed inset-0 z-40 bg-inverse-surface/20 md:hidden"
          onClick={() => setMoreOpen(false)}
        />
      ) : null}

      {moreOpen ? (
        <div
          id="mobile-nav-more"
          className="fixed inset-x-0 bottom-16 z-50 mx-container-margin mb-sm animate-fade-in-up rounded-2xl border border-outline-variant/20 bg-surface-container-lowest p-md shadow-deep md:hidden"
        >
          <p className="m-0 mb-sm text-label-sm font-semibold uppercase tracking-wider text-on-surface-variant">
            More
          </p>
          <div className="flex flex-col gap-xs">
            {SECONDARY_NAV.map((item) => (
              <MoreLink key={item.to} to={item.to} icon={item.icon} onNavigate={() => setMoreOpen(false)}>
                {item.label}
                {item.to === '/notifications' ? <UnreadBadge className="ml-auto" /> : null}
              </MoreLink>
            ))}
            {privileged
              ? PRIVILEGED_NAV.map((item) => (
                  <MoreLink
                    key={item.to}
                    to={item.to}
                    icon={item.icon}
                    onNavigate={() => setMoreOpen(false)}
                  >
                    {item.label}
                  </MoreLink>
                ))
              : null}
          </div>
        </div>
      ) : null}

      <nav
        aria-label="Primary"
        className="fixed inset-x-0 bottom-0 z-50 flex h-16 items-center justify-around border-t border-outline-variant/20 bg-surface/70 px-container-margin pb-safe shadow-nav-up backdrop-blur-xl md:hidden"
      >
        {PRIMARY_NAV.map((item) => (
          <MobileTab key={item.to} to={item.to} icon={item.icon} label={item.label} />
        ))}
        <button
          type="button"
          aria-expanded={moreOpen}
          aria-controls="mobile-nav-more"
          onClick={() => setMoreOpen((open) => !open)}
          className={cn(
            'flex flex-col items-center gap-0.5 rounded-full px-sm py-1 text-label-sm transition-all duration-fast motion-safe:active:scale-90',
            moreOpen
              ? 'bg-primary-container text-on-primary-container'
              : 'text-on-surface-variant',
          )}
        >
          <Icon name="more_horiz" filled={moreOpen} className="text-[22px] leading-none" />
          <span>More</span>
        </button>
      </nav>
    </>
  );
}

function MobileTab({ to, icon, label }: { to: string; icon: string; label: string }) {
  return (
    <NavLink
      to={to}
      className={({ isActive }) =>
        cn(
          'flex flex-col items-center gap-0.5 rounded-full px-sm py-1 text-label-sm no-underline transition-all duration-fast motion-safe:active:scale-90',
          isActive
            ? 'bg-primary-container px-md text-on-primary-container'
            : 'text-on-surface-variant',
        )
      }
    >
      {({ isActive }) => (
        <>
          <Icon name={icon} filled={isActive} className="text-[22px] leading-none" />
          <span>{label}</span>
        </>
      )}
    </NavLink>
  );
}

function MoreLink({
  to,
  icon,
  children,
  onNavigate,
}: {
  to: string;
  icon: string;
  children: ReactNode;
  onNavigate: () => void;
}) {
  return (
    <Link
      to={to}
      onClick={onNavigate}
      className="flex items-center gap-sm rounded-lg px-md py-sm text-body-md text-on-surface no-underline transition-colors hover:bg-surface-container"
    >
      <Icon name={icon} className="text-[20px] leading-none text-on-surface-variant" />
      {children}
    </Link>
  );
}
