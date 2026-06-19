import { Suspense, lazy, type ReactElement } from 'react';
import { ProfileSkeleton } from '@parkio/ui';
import { Navigate, createBrowserRouter } from 'react-router-dom';
import { ProtectedRoute } from '@/auth/ProtectedRoute';
import { RoleRoute } from '@/auth/RoleRoute';
import { RouteAccessibility } from '@/components/RouteAccessibility';
import { AppShell } from '@/components/shell/AppShell';
import { RouteFallback } from '@/components/RouteFallback';
// Eager: entry/auth routes and the default landing map keep the first paint fast
// without an extra chunk round-trip.
import { AccountPreparingPage } from '@/pages/AccountPreparingPage';
import { CheckEmailPage } from '@/pages/CheckEmailPage';
import { ForgotPasswordPage } from '@/pages/ForgotPasswordPage';
import { LoginPage } from '@/pages/LoginPage';
import { MapPage } from '@/pages/MapPage';
import { NotFoundPage } from '@/pages/NotFoundPage';
import { RegisterPage } from '@/pages/RegisterPage';
import { ResetPasswordPage } from '@/pages/ResetPasswordPage';
import { VerifyEmailPage } from '@/pages/VerifyEmailPage';

// Lazy: secondary routes are split into their own chunks to shrink the initial bundle.
const SpotDetailPage = lazy(() =>
  import('@/pages/SpotDetailPage').then((m) => ({ default: m.SpotDetailPage })),
);
const MySpotsPage = lazy(() =>
  import('@/pages/MySpotsPage').then((m) => ({ default: m.MySpotsPage })),
);
const UploadPage = lazy(() => import('@/pages/UploadPage').then((m) => ({ default: m.UploadPage })));
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
              Account
            </p>
            <h1 className="m-0 mt-sm text-headline-lg-mobile text-on-surface md:text-headline-lg">
              Settings &amp; Preferences
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

export const router = createBrowserRouter([{
  element: <RouteAccessibility />,
  children: [
  { path: '/', element: <Navigate to="/map" replace /> },
  { path: '/login', element: <LoginPage /> },
  { path: '/register', element: <RegisterPage /> },
  { path: '/forgot-password', element: <ForgotPasswordPage /> },
  { path: '/reset-password', element: <ResetPasswordPage /> },
  { path: '/check-email', element: <CheckEmailPage /> },
  { path: '/verify-email', element: <VerifyEmailPage /> },
  {
    element: <ProtectedRoute />,
    children: [
      { path: '/preparing', element: <AccountPreparingPage /> },
      {
        element: <AppShell />,
        children: [
          { path: '/map', element: <MapPage /> },
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
              { path: '/moderation', element: lazyRoute(<ModerationPage />) },
              { path: '/analytics', element: lazyRoute(<AnalyticsPage />) },
            ],
          },
        ],
      },
    ],
  },
  { path: '*', element: <NotFoundPage /> },
  ],
}]);
