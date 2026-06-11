import { hasPrivilegedRole } from '@parkio/types';
import { Card, PageShell } from '@parkio/ui';
import { Navigate, Outlet } from 'react-router-dom';
import { useAuthStore } from './store';

interface RoleRouteProps {
  /** When true, requires MODERATOR or ADMIN (gateway-aligned privileged roles). */
  requirePrivileged?: boolean;
}

export function RoleRoute({ requirePrivileged = false }: RoleRouteProps) {
  const roles = useAuthStore((s) => s.roles);
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);

  if (!isAuthenticated) {
    return <Navigate to="/login" replace />;
  }

  if (requirePrivileged && !hasPrivilegedRole(roles)) {
    return (
      <PageShell title="Access denied">
        <Card title="Forbidden">
          <p>This area requires a moderator or admin role.</p>
        </Card>
      </PageShell>
    );
  }

  return <Outlet />;
}
