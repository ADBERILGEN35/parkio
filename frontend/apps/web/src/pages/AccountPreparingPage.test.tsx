import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { http, HttpResponse } from 'msw';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { I18nextProvider } from 'react-i18next';
import {
  Outlet,
  RouterProvider,
  createMemoryRouter,
  type RouteObject,
} from 'react-router-dom';
import ts from 'typescript';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AppRuntimeProvider } from '@/app/AppRuntimeProvider';
import { createWebAppRuntime } from '@/app/runtime';
import {
  clearPendingProfile,
  setPendingProfile,
} from '@/auth/pendingProfile';
import i18n from '@/i18n';
import { DisposeTestRuntime } from '@/test/DisposeTestRuntime';
import { API_BASE, apiErrorBody, server } from '@/test/server';
import { signInAs } from '@/test/utils';
import { RoutePolicyBoundary } from '@/routing/RoutePolicyBoundary';
import {
  ROUTE_IDS,
  getRoutePath,
} from '@/routing/route-manifest';
import { AccountPreparingPage } from './AccountPreparingPage';

const meUser = {
  id: '6f9619ff-8b86-4d01-b42d-00cf4fc964ff',
  email: 'tester@parkio.dev',
  status: 'ACTIVE',
  roles: ['USER'],
};

/** Mirrors the post-register handoff: an authenticated session inside the grace window. */
function renderPreparing() {
  const routes: RouteObject[] = [
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
              id: ROUTE_IDS.PREPARING,
              path: getRoutePath(ROUTE_IDS.PREPARING),
              element: <AccountPreparingPage />,
            },
            {
              id: ROUTE_IDS.MAP,
              path: getRoutePath(ROUTE_IDS.MAP),
              element: <div>Map page stub</div>,
            },
          ],
        },
      ],
    },
  ];
  const runtime = createWebAppRuntime({
    baseURL: API_BASE,
    createRouter: () =>
      createMemoryRouter(routes, {
        initialEntries: [getRoutePath(ROUTE_IDS.PREPARING)],
      }),
  });
  signInAs(runtime, ['USER']);
  runtime.authStore.getState().beginProvisioning();
  const navigationTransitions: Array<{
    pathname: string;
    historyAction: string;
  }> = [];
  let previousPathname = runtime.router.state.location.pathname;
  runtime.router.subscribe((state) => {
    if (state.location.pathname !== previousPathname) {
      previousPathname = state.location.pathname;
      navigationTransitions.push({
        pathname: state.location.pathname,
        historyAction: state.historyAction,
      });
    }
  });

  const result = render(
    <I18nextProvider i18n={i18n}>
      <AppRuntimeProvider runtime={runtime}>
        <DisposeTestRuntime runtime={runtime} />
        <RouterProvider router={runtime.router} />
      </AppRuntimeProvider>
    </I18nextProvider>,
  );
  return { ...result, runtime, navigationTransitions };
}

function accountPreparingNavigationViolations(): string[] {
  const path = join(
    process.cwd(),
    'src/pages/AccountPreparingPage.tsx',
  );
  const sourceFile = ts.createSourceFile(
    path,
    readFileSync(path, 'utf8'),
    ts.ScriptTarget.Latest,
    true,
    ts.ScriptKind.TSX,
  );
  const violations: string[] = [];

  for (const statement of sourceFile.statements) {
    if (
      ts.isImportDeclaration(statement) &&
      ts.isStringLiteral(statement.moduleSpecifier) &&
      statement.moduleSpecifier.text === 'react-router-dom'
    ) {
      violations.push('react-router-dom import');
    }
  }

  function visit(node: ts.Node) {
    if (ts.isCallExpression(node)) {
      const target = node.expression;
      if (
        (ts.isIdentifier(target) &&
          (target.text === 'navigate' || target.text === 'redirect')) ||
        (ts.isPropertyAccessExpression(target) &&
          (target.name.text === 'navigate' ||
            target.name.text === 'redirect')) ||
        (ts.isElementAccessExpression(target) &&
          ts.isStringLiteral(target.argumentExpression) &&
          (target.argumentExpression.text === 'navigate' ||
            target.argumentExpression.text === 'redirect'))
      ) {
        violations.push(target.getText(sourceFile));
      }
    }
    ts.forEachChild(node, visit);
  }
  visit(sourceFile);

  return violations;
}

function notActive() {
  return HttpResponse.json(
    apiErrorBody('ACCOUNT_NOT_ACTIVE', 'Account is not active', 'trace-prov-1'),
    { status: 403 },
  );
}

describe('AccountPreparingPage', () => {
  afterEach(() => {
    clearPendingProfile();
    vi.useRealTimers();
  });

  it('contains no page-owned routing decision', () => {
    expect(accountPreparingNavigationViolations()).toEqual([]);
  });

  it('completes provisioning and lets the policy boundary replace to /map exactly once', async () => {
    server.use(http.get(`${API_BASE}/auth/me`, () => HttpResponse.json(meUser)));

    const { runtime, navigationTransitions } = renderPreparing();

    expect(await screen.findByText('Map page stub')).toBeInTheDocument();
    const state = runtime.authStore.getState();
    expect(state.suspended).toBe(false);
    expect(state.provisioning).toBe(false);
    expect(runtime.router.state.historyAction).toBe('REPLACE');
    expect(
      navigationTransitions.filter(
        (transition) =>
          transition.pathname === getRoutePath(ROUTE_IDS.MAP),
      ),
    ).toEqual([
      {
        pathname: getRoutePath(ROUTE_IDS.MAP),
        historyAction: 'REPLACE',
      },
    ]);
  });

  it('keeps profile-save failure visible until lifecycle completion triggers the boundary redirect', async () => {
    setPendingProfile({ displayName: 'New Driver' });
    server.use(
      http.get(`${API_BASE}/auth/me`, () => HttpResponse.json(meUser)),
      http.patch(`${API_BASE}/users/me`, () =>
        HttpResponse.json(
          apiErrorBody(
            'PROFILE_UPDATE_FAILED',
            'Profile update failed',
            'trace-profile-1',
          ),
          { status: 500 },
        ),
      ),
    );

    const { runtime, navigationTransitions } = renderPreparing();

    expect(
      await screen.findByRole('heading', {
        name: 'Your account is ready',
      }),
    ).toBeInTheDocument();
    expect(
      screen.getByText(
        'We could not save some profile details. You can update them anytime from Profile.',
      ),
    ).toBeInTheDocument();
    expect(runtime.authStore.getState().lifecycle).toBe('provisioning');
    expect(runtime.router.state.location.pathname).toBe(
      getRoutePath(ROUTE_IDS.PREPARING),
    );
    expect(navigationTransitions).toEqual([]);

    fireEvent.click(
      screen.getByRole('button', { name: 'Continue to Parkio' }),
    );

    expect(await screen.findByText('Map page stub')).toBeInTheDocument();
    expect(runtime.authStore.getState().lifecycle).toBe('authenticated');
    expect(runtime.router.state.historyAction).toBe('REPLACE');
    expect(navigationTransitions).toEqual([
      {
        pathname: getRoutePath(ROUTE_IDS.MAP),
        historyAction: 'REPLACE',
      },
    ]);
  });

  it('shows the preparing state and never marks suspended while provisioning', async () => {
    server.use(http.get(`${API_BASE}/auth/me`, () => notActive()));

    const { runtime } = renderPreparing();

    expect(await screen.findByText('Preparing your account')).toBeInTheDocument();
    expect(screen.getByText('This usually takes a few seconds.')).toBeInTheDocument();
    expect(screen.queryByText('Map page stub')).not.toBeInTheDocument();
    expect(runtime.router.state.location.pathname).toBe(
      getRoutePath(ROUTE_IDS.PREPARING),
    );
    // 403 ACCOUNT_NOT_ACTIVE during the grace window must NOT flip the global flag.
    expect(runtime.authStore.getState().suspended).toBe(false);
  });

  it('retries on ACCOUNT_NOT_ACTIVE and forwards once provisioning completes', async () => {
    vi.useFakeTimers();
    let calls = 0;
    server.use(
      http.get(`${API_BASE}/auth/me`, () => {
        calls += 1;
        return calls === 1 ? notActive() : HttpResponse.json(meUser);
      }),
    );

    const { runtime } = renderPreparing();
    // First attempt (immediate) 403 → schedule retry → second attempt 200 → /map.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1_100);
    });

    expect(screen.getByText('Map page stub')).toBeInTheDocument();
    expect(calls).toBeGreaterThanOrEqual(2);
    expect(runtime.authStore.getState().suspended).toBe(false);
  });

  it('shows retry + sign out after the readiness window times out', async () => {
    vi.useFakeTimers();
    server.use(http.get(`${API_BASE}/auth/me`, () => notActive()));

    const { runtime } = renderPreparing();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(13_500);
    });

    expect(
      screen.getByText('This is taking longer than expected.', { exact: false }),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /try again/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /sign out/i })).toBeInTheDocument();
    expect(runtime.authStore.getState().suspended).toBe(false);
  });

  it('retries from the timed-out state and forwards when ready', async () => {
    vi.useFakeTimers();
    let ready = false;
    server.use(
      http.get(`${API_BASE}/auth/me`, () => (ready ? HttpResponse.json(meUser) : notActive())),
    );

    renderPreparing();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(13_500);
    });
    expect(screen.getByRole('button', { name: /try again/i })).toBeInTheDocument();

    ready = true;
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /try again/i }));
      await vi.advanceTimersByTimeAsync(100);
    });

    expect(screen.getByText('Map page stub')).toBeInTheDocument();
  });
});
