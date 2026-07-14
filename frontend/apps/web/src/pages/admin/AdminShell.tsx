import { SoftBadge } from '@parkio/ui';
import { hasSuperAdminRole } from '@parkio/types';
import { NavLink, Outlet } from 'react-router-dom';
import { useAuthStore } from '@/auth/store';
import { frontendConfig } from '@/config/env';

const NAV = [
  { to: '/admin', label: 'Dashboard', end: true },
  { to: '/admin/users', label: 'Users' },
  { to: '/admin/security', label: 'Security' },
  { to: '/admin/moderation', label: 'Moderation' },
  { to: '/admin/analytics', label: 'Analytics' },
  { to: '/admin/audit', label: 'Audit' },
  { to: '/admin/system', label: 'System' },
] as const;

/**
 * Dedicated administration chrome: denser sidebar + environment warning.
 * Nested under AppShell's auth boundary via RoleRoute requireAdmin.
 */
export function AdminShell() {
  const email = useAuthStore((s) => s.user?.email);
  const roles = useAuthStore((s) => s.roles);
  const env = frontendConfig.appEnv;

  return (
    <div className="mx-auto flex w-full max-w-7xl flex-col gap-md px-md py-md md:flex-row md:px-xl md:py-lg">
      <aside className="shrink-0 md:w-56">
        <div className="sticky top-md space-y-md rounded-lg border border-outline-variant/40 bg-surface-container-low p-md">
          <div>
            <p className="m-0 text-label-md font-semibold uppercase tracking-wider text-primary">
              Administration
            </p>
            <p className="m-0 mt-xs truncate text-body-sm text-on-surface-variant" title={email ?? undefined}>
              {email ?? 'Administrator'}
            </p>
            <div className="mt-sm flex flex-wrap gap-xs">
              <SoftBadge tone={env === 'hosted-beta' || env === 'production' ? 'warning' : 'neutral'}>
                {env}
              </SoftBadge>
              {hasSuperAdminRole(roles) ? <SoftBadge tone="primary">SUPER_ADMIN</SoftBadge> : null}
            </div>
          </div>
          <nav className="flex flex-row flex-wrap gap-xs md:flex-col" aria-label="Admin">
            {NAV.map((item) => (
              <NavLink
                key={item.to}
                to={item.to}
                end={'end' in item ? item.end : false}
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
          <p className="m-0 text-body-sm text-on-surface-variant">
            Hosted-beta ops: destructive actions are audited. Prefer suspension over deletion.
          </p>
        </div>
      </aside>
      <main className="min-w-0 flex-1">
        <Outlet />
      </main>
    </div>
  );
}
