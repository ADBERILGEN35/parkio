import { hasAdminRole, hasPrivilegedRole } from '@parkio/types';
import { Card, PageShell } from '@parkio/ui';
import { useTranslation } from 'react-i18next';
import { Navigate, Outlet } from 'react-router-dom';
import { useAuthStore } from './store';

interface RoleRouteProps {
  /** When true, requires MODERATOR or ADMIN (gateway-aligned privileged roles). */
  requirePrivileged?: boolean;
  /** When true, requires ADMIN (platform analytics; gateway-aligned). */
  requireAdmin?: boolean;
}

export function RoleRoute({ requirePrivileged = false, requireAdmin = false }: RoleRouteProps) {
  const { t } = useTranslation('common');
  const roles = useAuthStore((s) => s.roles);
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);

  if (!isAuthenticated) {
    return <Navigate to="/login" replace />;
  }

  if (requireAdmin && !hasAdminRole(roles)) {
    return (
      <PageShell title={t('accessDenied.title')}>
        <Card title={t('accessDenied.forbidden')}>
          <p>{t('accessDenied.adminRequired')}</p>
        </Card>
      </PageShell>
    );
  }

  if (requirePrivileged && !hasPrivilegedRole(roles)) {
    return (
      <PageShell title={t('accessDenied.title')}>
        <Card title={t('accessDenied.forbidden')}>
          <p>{t('accessDenied.privilegedRequired')}</p>
        </Card>
      </PageShell>
    );
  }

  return <Outlet />;
}
