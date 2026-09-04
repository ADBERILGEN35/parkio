import type { User } from '@parkio/types';
import { act, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { I18nextProvider } from 'react-i18next';
import {
  Outlet,
  RouterProvider,
  createMemoryRouter,
  type RouteObject,
} from 'react-router-dom';
import { App } from '@/App';
import { AppRuntimeProvider } from '@/app/AppRuntimeProvider';
import {
  createWebAppRuntime,
  type WebAppRuntime,
} from '@/app/runtime';
import i18n from '@/i18n';
import { DisposeTestRuntime } from '@/test/DisposeTestRuntime';
import { createMemoryAppRouter } from './create-app-router';
import {
  AUTH_LIFECYCLE_DESTINATIONS,
  ROUTE_IDS,
  getRoutePath,
} from './route-manifest';
import { RoutePolicyBoundary } from './RoutePolicyBoundary';
import { AUTH_RETURN_QUERY_PARAM } from '@/auth/redirect';

const TEST_ROUTES: readonly RouteObject[] = [
  {
    id: ROUTE_IDS.ROOT,
    path: '/',
    element: <Outlet />,
    children: [
      {
        id: ROUTE_IDS.LOGIN,
        path: getRoutePath(ROUTE_IDS.LOGIN),
        element: <div>Login route</div>,
      },
      {
        id: ROUTE_IDS.CHECK_EMAIL,
        path: getRoutePath(ROUTE_IDS.CHECK_EMAIL),
        element: <div>Check email route</div>,
      },
      {
        id: ROUTE_IDS.PRIVACY,
        path: getRoutePath(ROUTE_IDS.PRIVACY),
        element: <div>Privacy route</div>,
      },
      {
        id: ROUTE_IDS.PROTECTED_BOUNDARY,
        element: <RoutePolicyBoundary />,
        children: [
          {
            id: ROUTE_IDS.MAP,
            path: getRoutePath(ROUTE_IDS.MAP),
            element: <div>Map route</div>,
          },
          {
            id: ROUTE_IDS.PROFILE,
            path: getRoutePath(ROUTE_IDS.PROFILE),
            element: <div>Profile route</div>,
          },
          {
            id: ROUTE_IDS.PREPARING,
            path: getRoutePath(ROUTE_IDS.PREPARING),
            element: <div>Preparing route</div>,
          },
          {
            id: ROUTE_IDS.ADMIN_MODERATION,
            path: getRoutePath(ROUTE_IDS.ADMIN_MODERATION),
            element: <div>Moderation route</div>,
          },
          {
            id: ROUTE_IDS.ADMIN_ANALYTICS,
            path: getRoutePath(ROUTE_IDS.ADMIN_ANALYTICS),
            element: <div>Analytics route</div>,
          },
        ],
      },
    ],
  },
];

function userWithRoles(roles: string[]): User {
  return {
    id: '6f9619ff-8b86-4d01-b42d-00cf4fc964ff',
    email: 'route-policy@parkio.dev',
    status: 'ACTIVE',
    roles,
  };
}

function createPolicyRuntime(pathname: string): WebAppRuntime {
  return createWebAppRuntime({
    baseURL: 'http://api.test/api/v1',
    createRouter: () =>
      createMemoryRouter([...TEST_ROUTES], {
        initialEntries: [pathname],
      }),
  });
}

function renderRuntimeRouter(runtime: WebAppRuntime) {
  return render(
    <I18nextProvider i18n={i18n}>
      <AppRuntimeProvider runtime={runtime}>
        <DisposeTestRuntime runtime={runtime} />
        <RouterProvider router={runtime.router} />
      </AppRuntimeProvider>
    </I18nextProvider>,
  );
}

function settleAnonymous(runtime: WebAppRuntime) {
  runtime.authStore.getState().endBootstrap();
}

function authenticate(runtime: WebAppRuntime, roles: string[] = ['USER']) {
  runtime.authStore
    .getState()
    .setSession('route-policy-token', userWithRoles(roles));
}

describe('RoutePolicyBoundary lifecycle policy', () => {
  it('waits without redirecting while protected bootstrap is pending', () => {
    const runtime = createPolicyRuntime(getRoutePath(ROUTE_IDS.PROFILE));

    renderRuntimeRouter(runtime);

    expect(
      screen.getByRole('status', { name: 'Loading…' }),
    ).toBeInTheDocument();
    expect(runtime.router.state.location.pathname).toBe(
      getRoutePath(ROUTE_IDS.PROFILE),
    );
  });

  it('replaces anonymous protected access with login and preserves safe query state', async () => {
    const runtime = createPolicyRuntime(
      `${getRoutePath(ROUTE_IDS.ADMIN_ANALYTICS)}?tab=security#fragment`,
    );
    settleAnonymous(runtime);

    renderRuntimeRouter(runtime);

    await waitFor(() =>
      expect(runtime.router.state.location.pathname).toBe(
        getRoutePath(AUTH_LIFECYCLE_DESTINATIONS.anonymous),
      ),
    );
    expect(runtime.router.state.historyAction).toBe('REPLACE');
    const loginParams = new URLSearchParams(runtime.router.state.location.search);
    expect(loginParams.get(AUTH_RETURN_QUERY_PARAM)).toBe(
      `${getRoutePath(ROUTE_IDS.ADMIN_ANALYTICS)}?tab=security`,
    );
    expect(
      screen.queryByText('This area requires an admin role.'),
    ).not.toBeInTheDocument();
  });

  it('preserves smartReturn, municipal params, and unrelated params on anonymous map redirects', async () => {
    const runtime = createPolicyRuntime(
      '/map?smartReturn=1&communityLayer=0&municipalAvailability=available&foo=bar#gone',
    );
    settleAnonymous(runtime);

    renderRuntimeRouter(runtime);

    await waitFor(() =>
      expect(runtime.router.state.location.pathname).toBe(
        getRoutePath(AUTH_LIFECYCLE_DESTINATIONS.anonymous),
      ),
    );

    const loginParams = new URLSearchParams(runtime.router.state.location.search);
    expect(loginParams.get(AUTH_RETURN_QUERY_PARAM)).toBe(
      '/map?smartReturn=1&communityLayer=0&municipalAvailability=available&foo=bar',
    );
  });

  it('renders the account-not-active surface at the requested protected URL', () => {
    const runtime = createPolicyRuntime(
      getRoutePath(ROUTE_IDS.ADMIN_ANALYTICS),
    );
    authenticate(runtime);
    runtime.authStore
      .getState()
      .markAccountRestricted('ACCOUNT_NOT_ACTIVE');

    renderRuntimeRouter(runtime);

    expect(
      screen.getByRole('heading', {
        name: 'Your account is not active',
      }),
    ).toBeInTheDocument();
    expect(runtime.router.state.location.pathname).toBe(
      getRoutePath(ROUTE_IDS.ADMIN_ANALYTICS),
    );
    expect(
      screen.queryByText('This area requires an admin role.'),
    ).not.toBeInTheDocument();
  });

  it('replaces account-not-verified protected access with check-email', async () => {
    const runtime = createPolicyRuntime(
      getRoutePath(ROUTE_IDS.ADMIN_ANALYTICS),
    );
    authenticate(runtime);
    runtime.authStore
      .getState()
      .markAccountRestricted('ACCOUNT_NOT_VERIFIED');

    renderRuntimeRouter(runtime);

    await waitFor(() =>
      expect(runtime.router.state.location.pathname).toBe(
        getRoutePath(
          AUTH_LIFECYCLE_DESTINATIONS.accountNotVerified,
        ),
      ),
    );
    expect(runtime.router.state.historyAction).toBe('REPLACE');
    expect(screen.getByText('Check email route')).toBeInTheDocument();
  });

  it('replaces provisioning access to ordinary protected routes with preparing', async () => {
    const runtime = createPolicyRuntime(
      getRoutePath(ROUTE_IDS.ADMIN_ANALYTICS),
    );
    authenticate(runtime);
    runtime.authStore.getState().beginProvisioning();

    renderRuntimeRouter(runtime);

    await waitFor(() =>
      expect(runtime.router.state.location.pathname).toBe(
        getRoutePath(AUTH_LIFECYCLE_DESTINATIONS.provisioning),
      ),
    );
    expect(runtime.router.state.historyAction).toBe('REPLACE');
    expect(screen.getByText('Preparing route')).toBeInTheDocument();
  });

  it('renders preparing only for a provisioning identity', () => {
    const runtime = createPolicyRuntime(
      getRoutePath(ROUTE_IDS.PREPARING),
    );
    authenticate(runtime);
    runtime.authStore.getState().beginProvisioning();

    renderRuntimeRouter(runtime);

    expect(screen.getByText('Preparing route')).toBeInTheDocument();
  });

  it('replaces authenticated access to preparing with the default route', async () => {
    const runtime = createPolicyRuntime(
      getRoutePath(ROUTE_IDS.PREPARING),
    );
    authenticate(runtime);

    renderRuntimeRouter(runtime);

    await waitFor(() =>
      expect(runtime.router.state.location.pathname).toBe(
        getRoutePath(
          AUTH_LIFECYCLE_DESTINATIONS.authenticatedDefault,
        ),
      ),
    );
    expect(runtime.router.state.historyAction).toBe('REPLACE');
    expect(screen.getByText('Map route')).toBeInTheDocument();
  });

  it('renders authenticated protected routes after lifecycle policy settles', () => {
    const runtime = createPolicyRuntime(getRoutePath(ROUTE_IDS.PROFILE));
    authenticate(runtime);

    renderRuntimeRouter(runtime);

    expect(screen.getByText('Profile route')).toBeInTheDocument();
  });

  it('redirects only after WP-02 unauthorized teardown completes', async () => {
    const runtime = createPolicyRuntime(getRoutePath(ROUTE_IDS.PROFILE));
    authenticate(runtime);
    renderRuntimeRouter(runtime);

    expect(screen.getByText('Profile route')).toBeInTheDocument();

    act(() => runtime.authSession.handleSdkUnauthorized());

    await waitFor(() =>
      expect(runtime.router.state.location.pathname).toBe(
        getRoutePath(AUTH_LIFECYCLE_DESTINATIONS.anonymous),
      ),
    );
    expect(runtime.authStore.getState().lifecycle).toBe('anonymous');
    expect(runtime.router.state.historyAction).toBe('REPLACE');
  });
});

describe('RoutePolicyBoundary manifest-owned role policy', () => {
  it('denies ordinary users on privileged routes without changing session identity', () => {
    const runtime = createPolicyRuntime(
      getRoutePath(ROUTE_IDS.ADMIN_MODERATION),
    );
    authenticate(runtime);

    renderRuntimeRouter(runtime);

    expect(
      screen.getByText(
        'This area requires a moderator or admin role.',
      ),
    ).toBeInTheDocument();
    expect(runtime.authStore.getState().lifecycle).toBe('authenticated');
  });

  it('allows moderators on manifest-owned privileged routes', () => {
    const runtime = createPolicyRuntime(
      getRoutePath(ROUTE_IDS.ADMIN_MODERATION),
    );
    authenticate(runtime, ['MODERATOR']);

    renderRuntimeRouter(runtime);

    expect(screen.getByText('Moderation route')).toBeInTheDocument();
  });

  it('denies moderators on administrator routes without changing session identity', () => {
    const runtime = createPolicyRuntime(
      getRoutePath(ROUTE_IDS.ADMIN_ANALYTICS),
    );
    authenticate(runtime, ['MODERATOR']);

    renderRuntimeRouter(runtime);

    expect(
      screen.getByText('This area requires an admin role.'),
    ).toBeInTheDocument();
    expect(runtime.authStore.getState().lifecycle).toBe('authenticated');
  });

  it('allows administrators on manifest-owned administrator routes', () => {
    const runtime = createPolicyRuntime(
      getRoutePath(ROUTE_IDS.ADMIN_ANALYTICS),
    );
    authenticate(runtime, ['ADMIN']);

    renderRuntimeRouter(runtime);

    expect(screen.getByText('Analytics route')).toBeInTheDocument();
  });
});

describe('public route availability', () => {
  it.each([
    'ACCOUNT_NOT_ACTIVE',
    'ACCOUNT_NOT_VERIFIED',
  ] as const)(
    'keeps public routes available for %s identities through App',
    (restriction) => {
      const runtime = createWebAppRuntime({
        baseURL: 'http://api.test/api/v1',
        createRouter: () =>
          createMemoryAppRouter({
            initialEntries: [getRoutePath(ROUTE_IDS.PRIVACY)],
          }),
      });
      authenticate(runtime);
      runtime.authStore
        .getState()
        .markAccountRestricted(restriction);

      render(
        <I18nextProvider i18n={i18n}>
          <App runtime={runtime} />
          <DisposeTestRuntime runtime={runtime} />
        </I18nextProvider>,
      );

      expect(
        screen.getByRole('heading', { name: 'Privacy Policy' }),
      ).toBeInTheDocument();
      expect(
        screen.queryByRole('heading', {
          name: 'Your account is not active',
        }),
      ).not.toBeInTheDocument();
    },
  );
});
