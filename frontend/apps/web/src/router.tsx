import { Suspense, lazy, type ReactElement } from 'react';
import { ProfileSkeleton } from '@parkio/ui';
import { createBrowserRouter, Navigate, type RouteObject } from 'react-router-dom';
import { ProtectedRoute } from '@/auth/ProtectedRoute';
import { RoleRoute } from '@/auth/RoleRoute';
import { RouteAccessibility } from '@/components/RouteAccessibility';
import { AppShell } from '@/components/shell/AppShell';
import { RouteFallback } from '@/components/RouteFallback';
// Eager: entry/auth routes keep the first paint fast without an extra chunk
// round-trip.
import { AccountPreparingPage } from '@/pages/AccountPreparingPage';
import { CheckEmailPage } from '@/pages/CheckEmailPage';
import { ForgotPasswordPage } from '@/pages/ForgotPasswordPage';
import { LoginPage } from '@/pages/LoginPage';
import { NotFoundPage } from '@/pages/NotFoundPage';
import { RegisterPage } from '@/pages/RegisterPage';
import { ResetPasswordPage } from '@/pages/ResetPasswordPage';
import { VerifyEmailPage } from '@/pages/VerifyEmailPage';
import { PrivacyPage, TermsPage } from '@/pages/LegalPage';

// Lazy: secondary routes are split into their own chunks to shrink the initial bundle.
const SpotDetailPage = lazy(() =>
  import('@/pages/SpotDetailPage').then((m) => ({ default: m.SpotDetailPage })),
);
const MySpotsPage = lazy(() =>
  import('@/pages/MySpotsPage').then((m) => ({ default: m.MySpotsPage })),
);
const UploadPage = lazy(() => import('@/pages/UploadPage').then((m) => ({ default: m.UploadPage })));
const MapPage = lazy(() => import('@/pages/MapPage').then((m) => ({ default: m.MapPage })));
const ProfilePage = lazy(() =>
  import('@/pages/ProfilePage').then((m) => ({ default: m.ProfilePage })),
);
const ReportsPage = lazy(() =>
  import('@/pages/ReportsPage').then((m) => ({ default: m.ReportsPage })),
);
const NotificationsPage = lazy(() =>
  import('@/pages/NotificationsPage').then((m) => ({ default: m.NotificationsPage })),
);
const GamificationPage = lazy(() =>
  import('@/pages/GamificationPage').then((m) => ({ default: m.GamificationPage })),
);
const LeaderboardPage = lazy(() =>
  import('@/pages/LeaderboardPage').then((m) => ({ default: m.LeaderboardPage })),
);
const ModerationPage = lazy(() =>
  import('@/pages/ModerationPage').then((m) => ({ default: m.ModerationPage })),
);
const AnalyticsPage = lazy(() =>
  import('@/pages/AnalyticsPage').then((m) => ({ default: m.AnalyticsPage })),
);
const AdminShell = lazy(() =>
  import('@/pages/admin/AdminShell').then((m) => ({ default: m.AdminShell })),
);
const AdminDashboardPage = lazy(() =>
  import('@/pages/admin/AdminDashboardPage').then((m) => ({ default: m.AdminDashboardPage })),
);
const AdminUsersPage = lazy(() =>
  import('@/pages/admin/AdminUsersPage').then((m) => ({ default: m.AdminUsersPage })),
);
const AdminUserDetailPage = lazy(() =>
  import('@/pages/admin/AdminUserDetailPage').then((m) => ({ default: m.AdminUserDetailPage })),
);
const AdminSecurityPage = lazy(() =>
  import('@/pages/admin/AdminSecurityPage').then((m) => ({ default: m.AdminSecurityPage })),
);
const AdminAuditPage = lazy(() =>
  import('@/pages/admin/AdminAuditPage').then((m) => ({ default: m.AdminAuditPage })),
);
const AdminSystemPage = lazy(() =>
  import('@/pages/admin/AdminSystemPage').then((m) => ({ default: m.AdminSystemPage })),
);

/** Wraps a lazily-loaded route element in a Suspense boundary with a shared fallback. */
function lazyRoute(element: ReactElement): ReactElement {
  return <Suspense fallback={<RouteFallback />}>{element}</Suspense>;
}

function profileRoute(element: ReactElement): ReactElement {
  return (
    <Suspense
      fallback={
        <div className="mx-auto w-full max-w-5xl px-md py-lg text-on-background md:px-xl">
          <header className="mb-lg">
            <p className="m-0 flex items-center gap-xs text-label-md font-semibold uppercase tracking-wider text-primary">
              {/* Skeleton eyebrow; full labels come from Settings once the chunk loads */}
              …
            </p>
            <h1 className="m-0 mt-sm text-headline-lg-mobile text-on-surface md:text-headline-lg">
              …
            </h1>
          </header>
          <ProfileSkeleton />
        </div>
      }
    >
      {element}
    </Suspense>
  );
}

export const routes: RouteObject[] = [{
  element: <RouteAccessibility />,
  children: [
  { path: '/login', element: <LoginPage /> },
  { path: '/register', element: <RegisterPage /> },
  { path: '/forgot-password', element: <ForgotPasswordPage /> },
  { path: '/reset-password', element: <ResetPasswordPage /> },
  { path: '/check-email', element: <CheckEmailPage /> },
  { path: '/verify-email', element: <VerifyEmailPage /> },
  { path: '/terms', element: <TermsPage /> },
  { path: '/privacy', element: <PrivacyPage /> },
  {
    element: <ProtectedRoute />,
    children: [
      // Product entry: the authenticated home is the map. Unauthenticated
      // visitors are bounced to /login by ProtectedRoute.
      { path: '/', element: <Navigate to="/map" replace /> },
      { path: '/preparing', element: <AccountPreparingPage /> },
      {
        element: <AppShell />,
        children: [
          { path: '/map', element: lazyRoute(<MapPage />) },
          { path: '/spots/:spotId', element: lazyRoute(<SpotDetailPage />) },
          { path: '/my-spots', element: lazyRoute(<MySpotsPage />) },
          { path: '/upload', element: lazyRoute(<UploadPage />) },
          { path: '/profile', element: profileRoute(<ProfilePage />) },
          { path: '/reports', element: lazyRoute(<ReportsPage />) },
          { path: '/notifications', element: lazyRoute(<NotificationsPage />) },
          { path: '/gamification', element: lazyRoute(<GamificationPage />) },
          { path: '/leaderboard', element: lazyRoute(<LeaderboardPage />) },
          {
            element: <RoleRoute requirePrivileged />,
            children: [
              { path: '/moderation', element: <Navigate to="/admin/moderation" replace /> },
              { path: '/admin/moderation', element: lazyRoute(<ModerationPage />) },
            ],
          },
          {
            element: <RoleRoute requireAdmin />,
            children: [
              { path: '/analytics', element: <Navigate to="/admin/analytics" replace /> },
              {
                path: '/admin',
                element: lazyRoute(<AdminShell />),
                children: [
                  { index: true, element: lazyRoute(<AdminDashboardPage />) },
                  { path: 'users', element: lazyRoute(<AdminUsersPage />) },
                  { path: 'users/:id', element: lazyRoute(<AdminUserDetailPage />) },
                  { path: 'security', element: lazyRoute(<AdminSecurityPage />) },
                  { path: 'analytics', element: lazyRoute(<AnalyticsPage />) },
                  { path: 'audit', element: lazyRoute(<AdminAuditPage />) },
                  { path: 'system', element: lazyRoute(<AdminSystemPage />) },
                ],
              },
            ],
          },
        ],
      },
    ],
  },
  { path: '*', element: <NotFoundPage /> },
  ],
}];

export const router = createBrowserRouter(routes);
