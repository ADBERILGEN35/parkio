import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { RouteFallback } from '@/components/RouteFallback';
import { useAuthStore } from './store';

export function ProtectedRoute() {
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);
  const bootstrapPending = useAuthStore((s) => s.bootstrapPending);
  const location = useLocation();

  // While the post-reload refresh is still resolving, do not redirect: a valid
  // session may be about to be restored from the HttpOnly cookie. Showing the
  // loader avoids a /login flash and an incorrect redirect during refresh.
  if (bootstrapPending) {
    return <RouteFallback />;
  }

  if (!isAuthenticated) {
    return <Navigate to="/login" replace state={{ from: location }} />;
  }

  return <Outlet />;
}
