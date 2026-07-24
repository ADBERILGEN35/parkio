import { SoftBadge } from '@parkio/ui';
import { hasSuperAdminRole } from '@parkio/types';
import { useTranslation } from 'react-i18next';
import { NavLink, Outlet } from 'react-router-dom';
import { useAuthStore } from '@/auth/store';
import { getAdminShellNav } from '@/components/shell/navConfig';
import { frontendConfig } from '@/config/env';

/**
 * Dedicated administration chrome: denser sidebar + environment warning.
 * RoutePolicyBoundary owns its manifest-derived administrator policy.
 */
export function AdminShell() {
  const { t } = useTranslation('admin');
  const email = useAuthStore((s) => s.user?.email);
  const roles = useAuthStore((s) => s.roles);
  const env = frontendConfig.appEnv;
  const nav = getAdminShellNav(t);

  return (
    <div className="mx-auto flex w-full max-w-7xl flex-col gap-md px-md py-md md:flex-row md:px-xl md:py-lg">
      <aside className="shrink-0 md:w-56">
        <div className="sticky top-md space-y-md rounded-lg border border-outline-variant/40 bg-surface-container-low p-md">
          <div>
            <p className="m-0 text-label-md font-semibold uppercase tracking-wider text-primary">
              {t('shell.title')}
            </p>
            <p className="m-0 mt-xs truncate text-body-sm text-on-surface-variant" title={email ?? undefined}>
              {email ?? t('shell.administratorFallback')}
            </p>
            <div className="mt-sm flex flex-wrap gap-xs">
              <SoftBadge tone={env === 'hosted-beta' || env === 'production' ? 'warning' : 'neutral'}>
                {env}
              </SoftBadge>
              {hasSuperAdminRole(roles) ? (
                <SoftBadge tone="primary">{t('roles.SUPER_ADMIN')}</SoftBadge>
              ) : null}
            </div>
          </div>
          <nav className="flex flex-row flex-wrap gap-xs md:flex-col" aria-label={t('shell.navAria')}>
            {nav.map((item) => (
              <NavLink
                key={item.id}
                to={item.to}
                end={item.end}
                className={({ isActive }) =>
                  [
                    'rounded-md px-sm py-xs text-body-md no-underline transition-colors',
                    isActive
                      ? 'bg-primary/10 font-semibold text-primary'
                      : 'text-on-surface hover:bg-surface-container-high',
                  ].join(' ')
                }
              >
                {item.label}
              </NavLink>
            ))}
          </nav>
          <p className="m-0 text-body-sm text-on-surface-variant">{t('shell.footerNote')}</p>
        </div>
      </aside>
      <main className="min-w-0 flex-1">
        <Outlet />
      </main>
    </div>
  );
}
