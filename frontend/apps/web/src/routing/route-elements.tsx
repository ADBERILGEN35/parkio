import { ProfileSkeleton } from '@parkio/ui';
import {
  lazy,
  type ComponentType,
  type LazyExoticComponent,
  type ReactElement,
} from 'react';
import { Outlet } from 'react-router-dom';
import { RouteFallback } from '@/components/RouteFallback';
import { AppShell } from '@/components/shell/AppShell';
import { AccountPreparingPage } from '@/pages/AccountPreparingPage';
import { AccountSuspendedPage } from '@/pages/AccountSuspendedPage';
import { CheckEmailPage } from '@/pages/CheckEmailPage';
import { ForgotPasswordPage } from '@/pages/ForgotPasswordPage';
import { PrivacyPage, TermsPage } from '@/pages/LegalPage';
import { LoginPage } from '@/pages/LoginPage';
import { NotFoundPage } from '@/pages/NotFoundPage';
import { RegisterPage } from '@/pages/RegisterPage';
import { ResetPasswordPage } from '@/pages/ResetPasswordPage';
import { VerifyEmailPage } from '@/pages/VerifyEmailPage';
import { AdminShell } from '@/pages/admin/AdminShell';
import {
  ROUTE_COMPONENT_KEYS,
  type RouteComponentKey,
  type RouteFallbackPolicy,
} from './route-manifest';
import { RoutePolicyBoundary } from './RoutePolicyBoundary';
import { RuntimeRouteRoot } from './RuntimeRouteRoot';

type LazyRouteComponent = LazyExoticComponent<ComponentType>;

interface RouteElementRegistration {
  readonly eagerComponent?: ComponentType;
  readonly lazyComponent?: LazyRouteComponent;
}

function eager(component: ComponentType): RouteElementRegistration {
  return Object.freeze({ eagerComponent: component });
}

function lazyComponent(
  loader: () => Promise<{ default: ComponentType }>,
): RouteElementRegistration {
  return Object.freeze({ lazyComponent: lazy(loader) });
}

function RouteGroupOutlet() {
  return <Outlet />;
}

const PrivilegedRouteGroupOutlet = RouteGroupOutlet.bind(null);
const AdministratorRouteGroupOutlet = RouteGroupOutlet.bind(null);

function createProfileRouteFallback(): ReactElement {
  return (
    <div className="mx-auto w-full max-w-5xl px-md py-lg text-on-background md:px-xl">
      <header className="mb-lg">
        <p className="m-0 flex items-center gap-xs text-label-md font-semibold uppercase tracking-wider text-primary">
          …
        </p>
        <h1 className="m-0 mt-sm text-headline-lg-mobile text-on-surface md:text-headline-lg">
          …
        </h1>
      </header>
      <ProfileSkeleton />
    </div>
  );
}

export const ROUTE_ELEMENT_REGISTRY: Readonly<
  Record<RouteComponentKey, RouteElementRegistration>
> = Object.freeze({
  [ROUTE_COMPONENT_KEYS.ROUTE_ACCESSIBILITY]: eager(RuntimeRouteRoot),
  [ROUTE_COMPONENT_KEYS.LOGIN_PAGE]: eager(LoginPage),
  [ROUTE_COMPONENT_KEYS.REGISTER_PAGE]: eager(RegisterPage),
  [ROUTE_COMPONENT_KEYS.FORGOT_PASSWORD_PAGE]: eager(ForgotPasswordPage),
  [ROUTE_COMPONENT_KEYS.RESET_PASSWORD_PAGE]: eager(ResetPasswordPage),
  [ROUTE_COMPONENT_KEYS.CHECK_EMAIL_PAGE]: eager(CheckEmailPage),
  [ROUTE_COMPONENT_KEYS.VERIFY_EMAIL_PAGE]: eager(VerifyEmailPage),
  [ROUTE_COMPONENT_KEYS.TERMS_PAGE]: eager(TermsPage),
  [ROUTE_COMPONENT_KEYS.PRIVACY_PAGE]: eager(PrivacyPage),
  [ROUTE_COMPONENT_KEYS.PROTECTED_BOUNDARY]: eager(RoutePolicyBoundary),
  [ROUTE_COMPONENT_KEYS.REDIRECT]: Object.freeze({}),
  [ROUTE_COMPONENT_KEYS.ACCOUNT_PREPARING_PAGE]: eager(AccountPreparingPage),
  [ROUTE_COMPONENT_KEYS.ACCOUNT_SUSPENDED_PAGE]: eager(AccountSuspendedPage),
  [ROUTE_COMPONENT_KEYS.APPLICATION_SHELL]: eager(AppShell),
  [ROUTE_COMPONENT_KEYS.MAP_PAGE]: lazyComponent(() =>
    import('@/pages/MapPage').then((module) => ({
      default: module.MapPage,
    })),
  ),
  [ROUTE_COMPONENT_KEYS.SPOT_DETAIL_PAGE]: lazyComponent(() =>
    import('@/pages/SpotDetailPage').then((module) => ({
      default: module.SpotDetailPage,
    })),
  ),
  [ROUTE_COMPONENT_KEYS.FACILITY_DETAIL_PAGE]: lazyComponent(() =>
    import('@/pages/MunicipalFacilityDetailPage').then((module) => ({
      default: module.MunicipalFacilityDetailPage,
    })),
  ),
  [ROUTE_COMPONENT_KEYS.MY_SPOTS_PAGE]: lazyComponent(() =>
    import('@/pages/MySpotsPage').then((module) => ({
      default: module.MySpotsPage,
    })),
  ),
  [ROUTE_COMPONENT_KEYS.UPLOAD_PAGE]: lazyComponent(() =>
    import('@/pages/UploadPage').then((module) => ({
      default: module.UploadPage,
    })),
  ),
  [ROUTE_COMPONENT_KEYS.PROFILE_PAGE]: lazyComponent(() =>
    import('@/pages/ProfilePage').then((module) => ({
      default: module.ProfilePage,
    })),
  ),
  [ROUTE_COMPONENT_KEYS.REPORTS_PAGE]: lazyComponent(() =>
    import('@/pages/ReportsPage').then((module) => ({
      default: module.ReportsPage,
    })),
  ),
  [ROUTE_COMPONENT_KEYS.NOTIFICATIONS_PAGE]: lazyComponent(() =>
    import('@/pages/NotificationsPage').then((module) => ({
      default: module.NotificationsPage,
    })),
  ),
  [ROUTE_COMPONENT_KEYS.GAMIFICATION_PAGE]: lazyComponent(() =>
    import('@/pages/GamificationPage').then((module) => ({
      default: module.GamificationPage,
    })),
  ),
  [ROUTE_COMPONENT_KEYS.LEADERBOARD_PAGE]: lazyComponent(() =>
    import('@/pages/LeaderboardPage').then((module) => ({
      default: module.LeaderboardPage,
    })),
  ),
  [ROUTE_COMPONENT_KEYS.PRIVILEGED_BOUNDARY]: eager(
    PrivilegedRouteGroupOutlet,
  ),
  [ROUTE_COMPONENT_KEYS.MODERATION_PAGE]: lazyComponent(() =>
    import('@/pages/ModerationPage').then((module) => ({
      default: module.ModerationPage,
    })),
  ),
  [ROUTE_COMPONENT_KEYS.ADMIN_BOUNDARY]: eager(
    AdministratorRouteGroupOutlet,
  ),
  [ROUTE_COMPONENT_KEYS.ADMIN_SHELL]: eager(AdminShell),
  [ROUTE_COMPONENT_KEYS.ADMIN_DASHBOARD_PAGE]: lazyComponent(() =>
    import('@/pages/admin/AdminDashboardPage').then((module) => ({
      default: module.AdminDashboardPage,
    })),
  ),
  [ROUTE_COMPONENT_KEYS.ADMIN_USERS_PAGE]: lazyComponent(() =>
    import('@/pages/admin/AdminUsersPage').then((module) => ({
      default: module.AdminUsersPage,
    })),
  ),
  [ROUTE_COMPONENT_KEYS.ADMIN_USER_DETAIL_PAGE]: lazyComponent(() =>
    import('@/pages/admin/AdminUserDetailPage').then((module) => ({
      default: module.AdminUserDetailPage,
    })),
  ),
  [ROUTE_COMPONENT_KEYS.ADMIN_SECURITY_PAGE]: lazyComponent(() =>
    import('@/pages/admin/AdminSecurityPage').then((module) => ({
      default: module.AdminSecurityPage,
    })),
  ),
  [ROUTE_COMPONENT_KEYS.ANALYTICS_PAGE]: lazyComponent(() =>
    import('@/pages/AnalyticsPage').then((module) => ({
      default: module.AnalyticsPage,
    })),
  ),
  [ROUTE_COMPONENT_KEYS.ADMIN_AUDIT_PAGE]: lazyComponent(() =>
    import('@/pages/admin/AdminAuditPage').then((module) => ({
      default: module.AdminAuditPage,
    })),
  ),
  [ROUTE_COMPONENT_KEYS.ADMIN_SYSTEM_PAGE]: lazyComponent(() =>
    import('@/pages/admin/AdminSystemPage').then((module) => ({
      default: module.AdminSystemPage,
    })),
  ),
  [ROUTE_COMPONENT_KEYS.NOT_FOUND_PAGE]: eager(NotFoundPage),
});

export const ROUTE_FALLBACK_REGISTRY: Readonly<
  Record<RouteFallbackPolicy, ReactElement | null>
> = Object.freeze({
  none: null,
  shared: <RouteFallback />,
  profile: createProfileRouteFallback(),
});
