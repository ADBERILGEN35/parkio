import { Navigate, createBrowserRouter } from 'react-router-dom';
import { ProtectedRoute } from '@/auth/ProtectedRoute';
import { RoleRoute } from '@/auth/RoleRoute';
import { AnalyticsPage } from '@/pages/AnalyticsPage';
import { GamificationPage } from '@/pages/GamificationPage';
import { LeaderboardPage } from '@/pages/LeaderboardPage';
import { LoginPage } from '@/pages/LoginPage';
import { MapPage } from '@/pages/MapPage';
import { ModerationPage } from '@/pages/ModerationPage';
import { MySpotsPage } from '@/pages/MySpotsPage';
import { NotificationsPage } from '@/pages/NotificationsPage';
import { ProfilePage } from '@/pages/ProfilePage';
import { RegisterPage } from '@/pages/RegisterPage';
import { SpotDetailPage } from '@/pages/SpotDetailPage';
import { UploadPage } from '@/pages/UploadPage';

export const router = createBrowserRouter([
  { path: '/', element: <Navigate to="/map" replace /> },
  { path: '/login', element: <LoginPage /> },
  { path: '/register', element: <RegisterPage /> },
  {
    element: <ProtectedRoute />,
    children: [
      { path: '/map', element: <MapPage /> },
      { path: '/spots/:spotId', element: <SpotDetailPage /> },
      { path: '/my-spots', element: <MySpotsPage /> },
      { path: '/upload', element: <UploadPage /> },
      { path: '/profile', element: <ProfilePage /> },
      { path: '/notifications', element: <NotificationsPage /> },
      { path: '/gamification', element: <GamificationPage /> },
      { path: '/leaderboard', element: <LeaderboardPage /> },
      {
        element: <RoleRoute requirePrivileged />,
        children: [
          { path: '/moderation', element: <ModerationPage /> },
          { path: '/analytics', element: <AnalyticsPage /> },
        ],
      },
    ],
  },
  { path: '*', element: <Navigate to="/map" replace /> },
]);
