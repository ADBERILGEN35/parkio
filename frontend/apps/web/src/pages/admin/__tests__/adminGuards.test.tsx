import { render, screen } from '@testing-library/react';
import { I18nextProvider } from 'react-i18next';
import {
  Outlet,
  RouterProvider,
  createMemoryRouter,
} from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { AppRuntimeProvider } from '@/app/AppRuntimeProvider';
import { createWebAppRuntime } from '@/app/runtime';
import i18n from '@/i18n';
import { RoutePolicyBoundary } from '@/routing/RoutePolicyBoundary';
import {
  ROUTE_IDS,
  getRoutePath,
} from '@/routing/route-manifest';
import { DisposeTestRuntime } from '@/test/DisposeTestRuntime';
import { signInAs } from '@/test/utils';

function renderAdminGuard(authRoles: string[]) {
  const runtime = createWebAppRuntime({
    baseURL: 'http://api.test/api/v1',
    createRouter: () =>
      createMemoryRouter(
        [
          {
            id: ROUTE_IDS.ROOT,
            path: '/',
            element: <Outlet />,
            children: [
              {
                id: ROUTE_IDS.LOGIN,
                path: getRoutePath(ROUTE_IDS.LOGIN),
                element: <div>Login page stub</div>,
              },
              {
                id: ROUTE_IDS.PROTECTED_BOUNDARY,
                element: <RoutePolicyBoundary />,
                children: [
                  {
                    id: ROUTE_IDS.ADMIN_DASHBOARD,
                    path: getRoutePath(ROUTE_IDS.ADMIN_DASHBOARD),
                    element: <div>Admin dashboard stub</div>,
                  },
                ],
              },
            ],
          },
        ],
        { initialEntries: [getRoutePath(ROUTE_IDS.ADMIN_DASHBOARD)] },
      ),
  });
  signInAs(runtime, authRoles);

  return render(
    <I18nextProvider i18n={i18n}>
      <AppRuntimeProvider runtime={runtime}>
        <DisposeTestRuntime runtime={runtime} />
        <RouterProvider router={runtime.router} />
      </AppRuntimeProvider>
    </I18nextProvider>,
  );
}

describe('admin route policy', () => {
  it('blocks ordinary users from /admin', () => {
    renderAdminGuard(['USER']);
    expect(
      screen.getByText('This area requires an admin role.'),
    ).toBeInTheDocument();
    expect(
      screen.queryByText('Admin dashboard stub'),
    ).not.toBeInTheDocument();
  });

  it('lets ADMIN access /admin', () => {
    renderAdminGuard(['ADMIN']);
    expect(screen.getByText('Admin dashboard stub')).toBeInTheDocument();
  });

  it('lets SUPER_ADMIN access /admin', () => {
    renderAdminGuard(['SUPER_ADMIN']);
    expect(screen.getByText('Admin dashboard stub')).toBeInTheDocument();
  });
});
