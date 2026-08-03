import { ProfileSkeleton } from '@parkio/ui';
import { render, within } from '@testing-library/react';
import {
  Children,
  Suspense,
  createElement,
  isValidElement,
  type ReactElement,
} from 'react';
import { Navigate, Outlet, type RouteObject } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
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
  compileAppRoutes,
  createAppRouter,
  createMemoryAppRouter,
} from './create-app-router';
import {
  ROUTE_COMPONENT_KEYS,
  ROUTE_IDS,
  ROUTE_MANIFEST,
  getRoutePath,
  type RouteComponentKey,
  type RouteId,
  type RouteManifestEntry,
} from './route-manifest';
import {
  ROUTE_ELEMENT_REGISTRY,
  ROUTE_FALLBACK_REGISTRY,
} from './route-elements';
import { RoutePolicyBoundary } from './RoutePolicyBoundary';
import { RuntimeRouteRoot } from './RuntimeRouteRoot';

vi.mock('@/pages/MapPage', () => ({
  MapPage: () => <span data-testid="lazy-route-marker">map-page</span>,
}));
vi.mock('@/pages/SpotDetailPage', () => ({
  SpotDetailPage: () => (
    <span data-testid="lazy-route-marker">spot-detail-page</span>
  ),
}));
vi.mock('@/pages/MunicipalFacilityDetailPage', () => ({
  MunicipalFacilityDetailPage: () => (
    <span data-testid="lazy-route-marker">facility-detail-page</span>
  ),
}));
vi.mock('@/pages/MySpotsPage', () => ({
  MySpotsPage: () => (
    <span data-testid="lazy-route-marker">my-spots-page</span>
  ),
}));
vi.mock('@/pages/UploadPage', () => ({
  UploadPage: () => <span data-testid="lazy-route-marker">upload-page</span>,
}));
vi.mock('@/pages/ProfilePage', () => ({
  ProfilePage: () => <span data-testid="lazy-route-marker">profile-page</span>,
}));
vi.mock('@/pages/ReportsPage', () => ({
  ReportsPage: () => <span data-testid="lazy-route-marker">reports-page</span>,
}));
vi.mock('@/pages/NotificationsPage', () => ({
  NotificationsPage: () => (
    <span data-testid="lazy-route-marker">notifications-page</span>
  ),
}));
vi.mock('@/pages/GamificationPage', () => ({
  GamificationPage: () => (
    <span data-testid="lazy-route-marker">gamification-page</span>
  ),
}));
vi.mock('@/pages/LeaderboardPage', () => ({
  LeaderboardPage: () => (
    <span data-testid="lazy-route-marker">leaderboard-page</span>
  ),
}));
vi.mock('@/pages/ModerationPage', () => ({
  ModerationPage: () => (
    <span data-testid="lazy-route-marker">moderation-page</span>
  ),
}));
vi.mock('@/pages/AnalyticsPage', () => ({
  AnalyticsPage: () => (
    <span data-testid="lazy-route-marker">analytics-page</span>
  ),
}));
vi.mock('@/pages/admin/AdminDashboardPage', () => ({
  AdminDashboardPage: () => (
    <span data-testid="lazy-route-marker">admin-dashboard-page</span>
  ),
}));
vi.mock('@/pages/admin/AdminUsersPage', () => ({
  AdminUsersPage: () => (
    <span data-testid="lazy-route-marker">admin-users-page</span>
  ),
}));
vi.mock('@/pages/admin/AdminUserDetailPage', () => ({
  AdminUserDetailPage: () => (
    <span data-testid="lazy-route-marker">admin-user-detail-page</span>
  ),
}));
vi.mock('@/pages/admin/AdminSecurityPage', () => ({
  AdminSecurityPage: () => (
    <span data-testid="lazy-route-marker">admin-security-page</span>
  ),
}));
vi.mock('@/pages/admin/AdminAuditPage', () => ({
  AdminAuditPage: () => (
    <span data-testid="lazy-route-marker">admin-audit-page</span>
  ),
}));
vi.mock('@/pages/admin/AdminSystemPage', () => ({
  AdminSystemPage: () => (
    <span data-testid="lazy-route-marker">admin-system-page</span>
  ),
}));

interface CompiledRoute {
  readonly route: RouteObject;
  readonly parentId: string | null;
}

const EXPECTED_COMPONENT_KEYS = {
  [ROUTE_IDS.ROOT]: ROUTE_COMPONENT_KEYS.ROUTE_ACCESSIBILITY,
  [ROUTE_IDS.LOGIN]: ROUTE_COMPONENT_KEYS.LOGIN_PAGE,
  [ROUTE_IDS.REGISTER]: ROUTE_COMPONENT_KEYS.REGISTER_PAGE,
  [ROUTE_IDS.FORGOT_PASSWORD]: ROUTE_COMPONENT_KEYS.FORGOT_PASSWORD_PAGE,
  [ROUTE_IDS.RESET_PASSWORD]: ROUTE_COMPONENT_KEYS.RESET_PASSWORD_PAGE,
  [ROUTE_IDS.CHECK_EMAIL]: ROUTE_COMPONENT_KEYS.CHECK_EMAIL_PAGE,
  [ROUTE_IDS.VERIFY_EMAIL]: ROUTE_COMPONENT_KEYS.VERIFY_EMAIL_PAGE,
  [ROUTE_IDS.TERMS]: ROUTE_COMPONENT_KEYS.TERMS_PAGE,
  [ROUTE_IDS.PRIVACY]: ROUTE_COMPONENT_KEYS.PRIVACY_PAGE,
  [ROUTE_IDS.PROTECTED_BOUNDARY]: ROUTE_COMPONENT_KEYS.PROTECTED_BOUNDARY,
  [ROUTE_IDS.AUTHENTICATED_ENTRY]: ROUTE_COMPONENT_KEYS.REDIRECT,
  [ROUTE_IDS.PREPARING]: ROUTE_COMPONENT_KEYS.ACCOUNT_PREPARING_PAGE,
  [ROUTE_IDS.ACCOUNT_NOT_ACTIVE_SURFACE]:
    ROUTE_COMPONENT_KEYS.ACCOUNT_SUSPENDED_PAGE,
  [ROUTE_IDS.APPLICATION_SHELL]: ROUTE_COMPONENT_KEYS.APPLICATION_SHELL,
  [ROUTE_IDS.MAP]: ROUTE_COMPONENT_KEYS.MAP_PAGE,
  [ROUTE_IDS.SPOT_DETAIL]: ROUTE_COMPONENT_KEYS.SPOT_DETAIL_PAGE,
  [ROUTE_IDS.FACILITY_DETAIL]: ROUTE_COMPONENT_KEYS.FACILITY_DETAIL_PAGE,
  [ROUTE_IDS.MY_SPOTS]: ROUTE_COMPONENT_KEYS.MY_SPOTS_PAGE,
  [ROUTE_IDS.UPLOAD]: ROUTE_COMPONENT_KEYS.UPLOAD_PAGE,
  [ROUTE_IDS.PROFILE]: ROUTE_COMPONENT_KEYS.PROFILE_PAGE,
  [ROUTE_IDS.REPORTS]: ROUTE_COMPONENT_KEYS.REPORTS_PAGE,
  [ROUTE_IDS.NOTIFICATIONS]: ROUTE_COMPONENT_KEYS.NOTIFICATIONS_PAGE,
  [ROUTE_IDS.GAMIFICATION]: ROUTE_COMPONENT_KEYS.GAMIFICATION_PAGE,
  [ROUTE_IDS.LEADERBOARD]: ROUTE_COMPONENT_KEYS.LEADERBOARD_PAGE,
  [ROUTE_IDS.PRIVILEGED_BOUNDARY]:
    ROUTE_COMPONENT_KEYS.PRIVILEGED_BOUNDARY,
  [ROUTE_IDS.MODERATION_ALIAS]: ROUTE_COMPONENT_KEYS.REDIRECT,
  [ROUTE_IDS.ADMIN_MODERATION]: ROUTE_COMPONENT_KEYS.MODERATION_PAGE,
  [ROUTE_IDS.ADMIN_BOUNDARY]: ROUTE_COMPONENT_KEYS.ADMIN_BOUNDARY,
  [ROUTE_IDS.ANALYTICS_ALIAS]: ROUTE_COMPONENT_KEYS.REDIRECT,
  [ROUTE_IDS.ADMIN_SHELL]: ROUTE_COMPONENT_KEYS.ADMIN_SHELL,
  [ROUTE_IDS.ADMIN_DASHBOARD]:
    ROUTE_COMPONENT_KEYS.ADMIN_DASHBOARD_PAGE,
  [ROUTE_IDS.ADMIN_USERS]: ROUTE_COMPONENT_KEYS.ADMIN_USERS_PAGE,
  [ROUTE_IDS.ADMIN_USER_DETAIL]:
    ROUTE_COMPONENT_KEYS.ADMIN_USER_DETAIL_PAGE,
  [ROUTE_IDS.ADMIN_SECURITY]: ROUTE_COMPONENT_KEYS.ADMIN_SECURITY_PAGE,
  [ROUTE_IDS.ADMIN_ANALYTICS]: ROUTE_COMPONENT_KEYS.ANALYTICS_PAGE,
  [ROUTE_IDS.ADMIN_AUDIT]: ROUTE_COMPONENT_KEYS.ADMIN_AUDIT_PAGE,
  [ROUTE_IDS.ADMIN_SYSTEM]: ROUTE_COMPONENT_KEYS.ADMIN_SYSTEM_PAGE,
  [ROUTE_IDS.NOT_FOUND]: ROUTE_COMPONENT_KEYS.NOT_FOUND_PAGE,
} satisfies Record<RouteId, RouteComponentKey>;

const EXPECTED_EAGER_COMPONENTS: Partial<
  Record<RouteComponentKey, ReactElement['type']>
> = {
  [ROUTE_COMPONENT_KEYS.ROUTE_ACCESSIBILITY]: RuntimeRouteRoot,
  [ROUTE_COMPONENT_KEYS.LOGIN_PAGE]: LoginPage,
  [ROUTE_COMPONENT_KEYS.REGISTER_PAGE]: RegisterPage,
  [ROUTE_COMPONENT_KEYS.FORGOT_PASSWORD_PAGE]: ForgotPasswordPage,
  [ROUTE_COMPONENT_KEYS.RESET_PASSWORD_PAGE]: ResetPasswordPage,
  [ROUTE_COMPONENT_KEYS.CHECK_EMAIL_PAGE]: CheckEmailPage,
  [ROUTE_COMPONENT_KEYS.VERIFY_EMAIL_PAGE]: VerifyEmailPage,
  [ROUTE_COMPONENT_KEYS.TERMS_PAGE]: TermsPage,
  [ROUTE_COMPONENT_KEYS.PRIVACY_PAGE]: PrivacyPage,
  [ROUTE_COMPONENT_KEYS.PROTECTED_BOUNDARY]: RoutePolicyBoundary,
  [ROUTE_COMPONENT_KEYS.ACCOUNT_PREPARING_PAGE]: AccountPreparingPage,
  [ROUTE_COMPONENT_KEYS.ACCOUNT_SUSPENDED_PAGE]: AccountSuspendedPage,
  [ROUTE_COMPONENT_KEYS.APPLICATION_SHELL]: AppShell,
  [ROUTE_COMPONENT_KEYS.ADMIN_SHELL]: AdminShell,
  [ROUTE_COMPONENT_KEYS.NOT_FOUND_PAGE]: NotFoundPage,
};

const EXPECTED_LAZY_MARKERS: Partial<Record<RouteComponentKey, string>> = {
  [ROUTE_COMPONENT_KEYS.MAP_PAGE]: 'map-page',
  [ROUTE_COMPONENT_KEYS.SPOT_DETAIL_PAGE]: 'spot-detail-page',
  [ROUTE_COMPONENT_KEYS.FACILITY_DETAIL_PAGE]: 'facility-detail-page',
  [ROUTE_COMPONENT_KEYS.MY_SPOTS_PAGE]: 'my-spots-page',
  [ROUTE_COMPONENT_KEYS.UPLOAD_PAGE]: 'upload-page',
  [ROUTE_COMPONENT_KEYS.PROFILE_PAGE]: 'profile-page',
  [ROUTE_COMPONENT_KEYS.REPORTS_PAGE]: 'reports-page',
  [ROUTE_COMPONENT_KEYS.NOTIFICATIONS_PAGE]: 'notifications-page',
  [ROUTE_COMPONENT_KEYS.GAMIFICATION_PAGE]: 'gamification-page',
  [ROUTE_COMPONENT_KEYS.LEADERBOARD_PAGE]: 'leaderboard-page',
  [ROUTE_COMPONENT_KEYS.MODERATION_PAGE]: 'moderation-page',
  [ROUTE_COMPONENT_KEYS.ADMIN_DASHBOARD_PAGE]: 'admin-dashboard-page',
  [ROUTE_COMPONENT_KEYS.ADMIN_USERS_PAGE]: 'admin-users-page',
  [ROUTE_COMPONENT_KEYS.ADMIN_USER_DETAIL_PAGE]: 'admin-user-detail-page',
  [ROUTE_COMPONENT_KEYS.ADMIN_SECURITY_PAGE]: 'admin-security-page',
  [ROUTE_COMPONENT_KEYS.ANALYTICS_PAGE]: 'analytics-page',
  [ROUTE_COMPONENT_KEYS.ADMIN_AUDIT_PAGE]: 'admin-audit-page',
  [ROUTE_COMPONENT_KEYS.ADMIN_SYSTEM_PAGE]: 'admin-system-page',
};

function flattenRoutes(
  routes: readonly RouteObject[],
  parentId: string | null = null,
): readonly CompiledRoute[] {
  return routes.flatMap((route) => [
    { route, parentId },
    ...flattenRoutes(route.children ?? [], route.id ?? null),
  ]);
}

function routeById(
  routes: readonly RouteObject[],
  routeId: RouteId,
): RouteObject {
  const route = flattenRoutes(routes).find(
    (candidate) => candidate.route.id === routeId,
  )?.route;
  if (!route) {
    throw new Error(`Compiled route '${routeId}' was not found.`);
  }
  return route;
}

function elementOf(route: RouteObject): ReactElement {
  if (!isValidElement(route.element)) {
    throw new Error(`Compiled route '${route.id}' does not own an element.`);
  }
  return route.element;
}

function structuralProjection(routes: readonly RouteObject[]) {
  return flattenRoutes(routes).map(({ route, parentId }) => ({
    id: route.id,
    parentId,
    path: route.path,
    index: route.index === true,
  }));
}

function manifestProjection(entry: RouteManifestEntry) {
  return {
    id: entry.id,
    parentId: entry.parentId,
    path: entry.kind === 'path' ? entry.path : undefined,
    index: entry.kind === 'index',
  };
}

function componentKeyForElement(element: ReactElement): RouteComponentKey {
  const registered = Object.entries(ROUTE_ELEMENT_REGISTRY).find(
    ([, registration]) => {
      if (element.type === Suspense) {
        const child = element.props.children;
        return (
          isValidElement(child) &&
          registration.lazyComponent === child.type
        );
      }
      return registration.eagerComponent === element.type;
    },
  );
  if (!registered) {
    throw new Error('Compiled element is not owned by the element registry.');
  }
  return registered[0] as RouteComponentKey;
}

function fallbackKeyForElement(element: ReactElement): string {
  const registered = Object.entries(ROUTE_FALLBACK_REGISTRY).find(
    ([, fallback]) => fallback === element.props.fallback,
  );
  if (!registered) {
    throw new Error('Compiled fallback is not owned by the fallback registry.');
  }
  return registered[0];
}

function semanticProjection(routes: readonly RouteObject[]) {
  return flattenRoutes(routes).map(({ route, parentId }) => {
    const element = elementOf(route);
    const compiledElement =
      element.type === Navigate
        ? {
            kind: 'redirect',
            to: element.props.to,
            replace: element.props.replace === true,
          }
        : {
            kind: element.type === Suspense ? 'lazy' : 'eager',
            componentKey: componentKeyForElement(element),
            fallback:
              element.type === Suspense
                ? fallbackKeyForElement(element)
                : 'none',
          };
    return {
      id: route.id,
      parentId,
      path: route.path,
      index: route.index === true,
      element: compiledElement,
    };
  });
}

function manifestSemanticProjection(entry: RouteManifestEntry) {
  return {
    ...manifestProjection(entry),
    element: entry.redirect
      ? {
          kind: 'redirect',
          to: getRoutePath(entry.redirect.targetId),
          replace: entry.redirect.replace,
        }
      : {
          kind: entry.load,
          componentKey: entry.componentKey,
          fallback: entry.load === 'lazy' ? entry.fallback : 'none',
        },
  };
}

async function assertRegistryMappings(
  registry: typeof ROUTE_ELEMENT_REGISTRY,
): Promise<void> {
  for (const [componentKey, expectedComponent] of Object.entries(
    EXPECTED_EAGER_COMPONENTS,
  )) {
    if (
      registry[componentKey as RouteComponentKey].eagerComponent !==
      expectedComponent
    ) {
      throw new Error(`Incorrect eager registration for '${componentKey}'.`);
    }
  }

  await Promise.all(
    Object.entries(EXPECTED_LAZY_MARKERS).map(
      async ([componentKey, expectedMarker]) => {
        const registration = registry[componentKey as RouteComponentKey];
        if (!registration.lazyComponent) {
          throw new Error(`Missing lazy registration for '${componentKey}'.`);
        }
        const view = render(
          <Suspense fallback={null}>
            {createElement(registration.lazyComponent)}
          </Suspense>,
        );
        try {
          const marker = await within(view.container).findByTestId(
            'lazy-route-marker',
          );
          if (marker.textContent !== expectedMarker) {
            throw new Error(
              `Incorrect lazy registration for '${componentKey}'.`,
            );
          }
        } finally {
          view.unmount();
        }
      },
    ),
  );

  const privileged = registry[
    ROUTE_COMPONENT_KEYS.PRIVILEGED_BOUNDARY
  ].eagerComponent;
  const administrator = registry[
    ROUTE_COMPONENT_KEYS.ADMIN_BOUNDARY
  ].eagerComponent;
  if (!privileged || !administrator) {
    throw new Error('Missing role boundary registration.');
  }
  if (privileged === administrator) {
    throw new Error('Role group registrations must retain distinct identities.');
  }
  const privilegedElement = (privileged as () => ReactElement)();
  const administratorElement = (administrator as () => ReactElement)();
  if (privilegedElement.type !== Outlet) {
    throw new Error('Incorrect privileged boundary registration.');
  }
  if (administratorElement.type !== Outlet) {
    throw new Error('Incorrect administrator boundary registration.');
  }
}

describe('canonical route compiler', () => {
  it('owns the exact component key for every manifest route ID', () => {
    expect(
      Object.fromEntries(
        ROUTE_MANIFEST.map((entry) => [entry.id, entry.componentKey]),
      ),
    ).toEqual(EXPECTED_COMPONENT_KEYS);
  });

  it('compiles every manifest node exactly once without redefining paths', () => {
    const compiled = flattenRoutes(compileAppRoutes());
    const compiledIds = compiled.map(({ route }) => route.id);

    expect(compiledIds).toHaveLength(ROUTE_MANIFEST.length);
    expect(compiledIds).toHaveLength(new Set(compiledIds).size);
    expect(structuralProjection(compileAppRoutes())).toEqual(
      ROUTE_MANIFEST.map(manifestProjection),
    );
  });

  it('preserves every manifest parent and child relationship', () => {
    const compiled = flattenRoutes(compileAppRoutes());

    for (const entry of ROUTE_MANIFEST) {
      expect(
        compiled.find(({ route }) => route.id === entry.id)?.parentId,
      ).toBe(entry.parentId);
    }
  });

  it('composes the root, application shell, administrator shell, and catch-all elements', () => {
    const routes = compileAppRoutes();

    expect(elementOf(routeById(routes, ROUTE_IDS.ROOT)).type).toBe(
      RuntimeRouteRoot,
    );
    expect(elementOf(routeById(routes, ROUTE_IDS.APPLICATION_SHELL)).type).toBe(
      AppShell,
    );
    expect(elementOf(routeById(routes, ROUTE_IDS.ADMIN_SHELL)).type).toBe(
      AdminShell,
    );
    expect(elementOf(routeById(routes, ROUTE_IDS.NOT_FOUND)).type).toBe(
      NotFoundPage,
    );
  });

  it('compiles only manifest-owned redirects with replacement semantics', () => {
    const routes = compileAppRoutes();
    const redirectEntries = ROUTE_MANIFEST.filter((entry) => entry.redirect);

    expect(redirectEntries.map((entry) => entry.id)).toEqual([
      ROUTE_IDS.AUTHENTICATED_ENTRY,
      ROUTE_IDS.MODERATION_ALIAS,
      ROUTE_IDS.ANALYTICS_ALIAS,
    ]);

    for (const entry of redirectEntries) {
      const element = elementOf(routeById(routes, entry.id));
      expect(element.type).toBe(Navigate);
      expect(element.props).toMatchObject({
        to: getRoutePath(entry.redirect!.targetId),
        replace: true,
      });
    }
  });

  it('composes eager and lazy elements only from manifest loading metadata', () => {
    const routes = compileAppRoutes();

    for (const entry of ROUTE_MANIFEST) {
      const element = elementOf(routeById(routes, entry.id));
      if (entry.load === 'lazy') {
        expect(element.type).toBe(Suspense);
      } else {
        expect(element.type).not.toBe(Suspense);
      }
    }
  });

  it('uses the shared lazy fallback with only the profile-specific exception', () => {
    const routes = compileAppRoutes();

    for (const entry of ROUTE_MANIFEST.filter(
      (candidate) => candidate.load === 'lazy',
    )) {
      const element = elementOf(routeById(routes, entry.id));
      const fallback = element.props.fallback;
      expect(isValidElement(fallback)).toBe(true);
      if (!isValidElement(fallback)) {
        continue;
      }
      expect(fallback.type).toBe(
        entry.fallback === 'profile' ? 'div' : RouteFallback,
      );
    }

    const profileFallback = elementOf(
      routeById(routes, ROUTE_IDS.PROFILE),
    ).props.fallback;
    expect(
      Children.toArray(profileFallback.props.children).some(
        (child) => isValidElement(child) && child.type === ProfileSkeleton,
      ),
    ).toBe(true);
  });

  it('compiles every route through its exact manifest-owned registry entry', () => {
    expect(semanticProjection(compileAppRoutes())).toEqual(
      ROUTE_MANIFEST.map(manifestSemanticProjection),
    );
  });
});

describe('element registry integrity', () => {
  it('owns the exact eager components, lazy loaders, and role boundaries', async () => {
    await expect(
      assertRegistryMappings(ROUTE_ELEMENT_REGISTRY),
    ).resolves.toBeUndefined();
  });

  it('deterministically rejects swapped eager and lazy registrations', async () => {
    const eagerSwap = {
      ...ROUTE_ELEMENT_REGISTRY,
      [ROUTE_COMPONENT_KEYS.LOGIN_PAGE]:
        ROUTE_ELEMENT_REGISTRY[ROUTE_COMPONENT_KEYS.REGISTER_PAGE],
      [ROUTE_COMPONENT_KEYS.REGISTER_PAGE]:
        ROUTE_ELEMENT_REGISTRY[ROUTE_COMPONENT_KEYS.LOGIN_PAGE],
    };
    const lazySwap = {
      ...ROUTE_ELEMENT_REGISTRY,
      [ROUTE_COMPONENT_KEYS.MAP_PAGE]:
        ROUTE_ELEMENT_REGISTRY[ROUTE_COMPONENT_KEYS.UPLOAD_PAGE],
      [ROUTE_COMPONENT_KEYS.UPLOAD_PAGE]:
        ROUTE_ELEMENT_REGISTRY[ROUTE_COMPONENT_KEYS.MAP_PAGE],
    };

    await expect(assertRegistryMappings(eagerSwap)).rejects.toThrow(
      "Incorrect eager registration for 'login-page'.",
    );
    await expect(assertRegistryMappings(lazySwap)).rejects.toThrow(
      "Incorrect lazy registration for 'map-page'.",
    );
  });
});

describe('router factories', () => {
  it('creates browser and memory routers with identical compiled semantics', () => {
    const browserRouter = createAppRouter();
    const memoryRouter = createMemoryAppRouter({
      initialEntries: [getRoutePath(ROUTE_IDS.LOGIN)],
    });
    const expected = ROUTE_MANIFEST.map(manifestSemanticProjection);

    expect(semanticProjection(browserRouter.routes)).toEqual(expected);
    expect(semanticProjection(memoryRouter.routes)).toEqual(expected);
    expect(semanticProjection(browserRouter.routes)).toEqual(
      semanticProjection(memoryRouter.routes),
    );

    browserRouter.dispose();
    memoryRouter.dispose();
  });

  it('creates deterministic memory routers without shared mutable state', async () => {
    const first = createMemoryAppRouter({
      initialEntries: [getRoutePath(ROUTE_IDS.LOGIN)],
    });
    const second = createMemoryAppRouter({
      initialEntries: [getRoutePath(ROUTE_IDS.PRIVACY)],
    });

    expect(first).not.toBe(second);
    expect(first.routes).not.toBe(second.routes);
    expect(first.state.location.pathname).toBe(
      getRoutePath(ROUTE_IDS.LOGIN),
    );
    expect(second.state.location.pathname).toBe(
      getRoutePath(ROUTE_IDS.PRIVACY),
    );

    await first.navigate(getRoutePath(ROUTE_IDS.TERMS));

    expect(first.state.location.pathname).toBe(
      getRoutePath(ROUTE_IDS.TERMS),
    );
    expect(second.state.location.pathname).toBe(
      getRoutePath(ROUTE_IDS.PRIVACY),
    );

    first.dispose();
    second.dispose();
  });
});
