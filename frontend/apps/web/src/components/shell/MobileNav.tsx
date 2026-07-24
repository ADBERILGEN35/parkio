import { Icon, cn } from '@parkio/ui';
import { useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, NavLink } from 'react-router-dom';
import { useAuthStore } from '@/auth/store';
import { ROUTE_IDS } from '@/routing/route-manifest';
import { getPrimaryNav, getSecondaryNav, getStaffNavItems } from './navConfig';
import { UnreadBadge } from './UnreadBadge';

/** Fixed bottom tab bar — DESIGN_SYSTEM §2.1 BottomNavBar (mobile). */
export function MobileNav() {
  const { t } = useTranslation('navigation');
  const roles = useAuthStore((s) => s.roles);
  const primaryNav = getPrimaryNav(t);
  const secondaryNav = getSecondaryNav(t);
  const staffNav = getStaffNavItems(roles, t);
  const [moreOpen, setMoreOpen] = useState(false);

  return (
    <>
      {moreOpen ? (
        <button
          type="button"
          aria-label={t('closeMenu')}
          className="fixed inset-0 z-40 bg-inverse-surface/20 md:hidden"
          onClick={() => setMoreOpen(false)}
        />
      ) : null}

      {moreOpen ? (
        <div
          id="mobile-nav-more"
          className="fixed inset-x-0 bottom-[var(--parkio-mobile-nav-offset)] z-50 mx-container-margin mb-sm animate-fade-in-up rounded-2xl border border-outline-variant/20 bg-surface-container-lowest p-md shadow-deep md:hidden"
        >
          <p className="m-0 mb-sm text-label-sm font-semibold uppercase tracking-wider text-on-surface-variant">
            {t('more')}
          </p>
          <div className="flex flex-col gap-xs">
            {secondaryNav.map((item) => (
              <MoreLink key={item.to} to={item.to} icon={item.icon} onNavigate={() => setMoreOpen(false)}>
                {item.label}
                {item.id === ROUTE_IDS.NOTIFICATIONS ? (
                  <UnreadBadge className="ml-auto" />
                ) : null}
              </MoreLink>
            ))}
            {staffNav.map((item) => (
              <MoreLink
                key={item.to}
                to={item.to}
                icon={item.icon}
                onNavigate={() => setMoreOpen(false)}
              >
                {item.label}
              </MoreLink>
            ))}
          </div>
        </div>
      ) : null}

      <nav
        aria-label={t('primaryAria')}
        className="fixed inset-x-0 bottom-0 z-50 flex h-16 items-center justify-around border-t border-outline-variant/20 bg-surface/70 px-container-margin pb-safe shadow-nav-up backdrop-blur-xl md:hidden"
      >
        {primaryNav.map((item) => (
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
          <span>{t('more')}</span>
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
