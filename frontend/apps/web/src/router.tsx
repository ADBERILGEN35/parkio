import { Suspense, lazy, type ReactElement } from 'react';
import { Navigate, createBrowserRouter } from 'react-router-dom';
import { ProtectedRoute } from '@/auth/ProtectedRoute';
import { RoleRoute } from '@/auth/RoleRoute';
import { RouteFallback } from '@/components/RouteFallback';
// Eager: entry/auth routes and the default landing map keep the first paint fast
// without an extra chunk round-trip.
import { AccountPreparingPage } from '@/pages/AccountPreparingPage';
import { LoginPage } from '@/pages/LoginPage';
import { MapPage } from '@/pages/MapPage';
import { RegisterPage } from '@/pages/RegisterPage';

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

export const router = createBrowserRouter([
  { path: '/', element: <Navigate to="/map" replace /> },
  { path: '/login', element: <LoginPage /> },
  { path: '/register', element: <RegisterPage /> },
  {
    element: <ProtectedRoute />,
    children: [
      { path: '/preparing', element: <AccountPreparingPage /> },
      { path: '/map', element: <MapPage /> },
      { path: '/spots/:spotId', element: lazyRoute(<SpotDetailPage />) },
      { path: '/my-spots', element: lazyRoute(<MySpotsPage />) },
      { path: '/upload', element: lazyRoute(<UploadPage />) },
      { path: '/profile', element: lazyRoute(<ProfilePage />) },
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
  { path: '*', element: <Navigate to="/map" replace /> },
]);
