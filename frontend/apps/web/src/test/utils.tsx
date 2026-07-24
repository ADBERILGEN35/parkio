import type { ParkioLocale, User } from '@parkio/types';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import { createElement, type ReactNode } from 'react';
import { I18nextProvider } from 'react-i18next';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { AppRuntimeProvider } from '@/app/AppRuntimeProvider';
import { createWebAppRuntime, type WebAppRuntime } from '@/app/runtime';
import i18n from '@/i18n';
import {
  createMemoryAppRouter,
  type AppMemoryRouterOptions,
} from '@/routing/create-app-router';
import { DisposeTestRuntime } from './DisposeTestRuntime';

/** Fresh client per test — no retries, so error states are deterministic and fast. */
export function createTestQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
}

type TestInitialEntries = NonNullable<
  AppMemoryRouterOptions['initialEntries']
>;

interface TestRouteController {
  element: ReactNode;
}

const TEST_ROUTE_CONTROLLER = Symbol('parkio.test-route-controller');

type TestRouter = WebAppRuntime['router'] & {
  readonly [TEST_ROUTE_CONTROLLER]: TestRouteController;
};

function createTestMemoryRouter(initialEntries: TestInitialEntries): TestRouter {
  const controller: TestRouteController = { element: null };
  const router = createMemoryRouter(
    [{ path: '*', element: createElement(() => controller.element) }],
    { initialEntries: [...initialEntries] },
  ) as TestRouter;
  Object.defineProperty(router, TEST_ROUTE_CONTROLLER, {
    value: controller,
  });
  return router;
}

function setRuntimeRoute(
  runtime: WebAppRuntime,
  element: ReactNode,
  initialEntries: TestInitialEntries,
) {
  const router = runtime.router as Partial<TestRouter>;
  const controller = router[TEST_ROUTE_CONTROLLER];
  if (!controller) {
    throw new Error(
      'renderWithProviders requires a runtime created by createTestAppRuntime.',
    );
  }

  controller.element = element;
  const requestedLocation = initialEntries.at(-1);
  if (requestedLocation) {
    const currentLocation = `${runtime.router.state.location.pathname}${runtime.router.state.location.search}${runtime.router.state.location.hash}`;
    const requestedPath =
      typeof requestedLocation === 'string'
        ? requestedLocation
        : `${requestedLocation.pathname ?? '/'}${requestedLocation.search ?? ''}${requestedLocation.hash ?? ''}`;
    if (currentLocation !== requestedPath) {
      if (typeof requestedLocation === 'string') {
        void runtime.router.navigate(requestedLocation, { replace: true });
      } else {
        void runtime.router.navigate(
          {
            pathname: requestedLocation.pathname,
            search: requestedLocation.search,
            hash: requestedLocation.hash,
          },
          { replace: true, state: requestedLocation.state },
        );
      }
    }
  }
}

export function createTestAppRuntime(
  queryClient = createTestQueryClient(),
  initialEntries: TestInitialEntries = ['/'],
): WebAppRuntime {
  const runtime = createWebAppRuntime({
    queryClient,
    createRouter: () => createTestMemoryRouter(initialEntries),
  });
  // General component tests start after the application's bootstrap attempt.
  // Bootstrap-specific tests explicitly restore the pending state they exercise.
  runtime.authStore.getState().endBootstrap();
  return runtime;
}

export function createTestAppRuntimeWithAppRouter(
  queryClient = createTestQueryClient(),
  initialEntries: TestInitialEntries = ['/'],
): WebAppRuntime {
  const runtime = createWebAppRuntime({
    queryClient,
    createRouter: () =>
      createMemoryAppRouter({ initialEntries: [...initialEntries] }),
  });
  runtime.authStore.getState().endBootstrap();
  return runtime;
}

export function renderWithProviders(
  ui: ReactNode,
  {
    authRoles,
    initialEntries = ['/'],
    runtime: providedRuntime,
  }: {
    authRoles?: string[];
    initialEntries?: TestInitialEntries;
    runtime?: WebAppRuntime;
  } = {},
) {
  const runtime =
    providedRuntime ??
    createTestAppRuntime(createTestQueryClient(), initialEntries);
  if (authRoles) {
    signInAs(runtime, authRoles);
  }
  setRuntimeRoute(runtime, ui, initialEntries);
  const queryClient = runtime.queryClient;
  const result = render(
    <I18nextProvider i18n={i18n}>
      <AppRuntimeProvider runtime={runtime}>
        <DisposeTestRuntime runtime={runtime} />
        <QueryClientProvider client={queryClient}>
          <RouterProvider router={runtime.router} />
        </QueryClientProvider>
      </AppRuntimeProvider>
    </I18nextProvider>,
  );
  return { ...result, queryClient, router: runtime.router, runtime };
}

export function resetAuth(runtime: WebAppRuntime) {
  runtime.authStore.getState().clearSession();
}

export function signInAs(runtime: WebAppRuntime, roles: string[]) {
  const user: User = {
    id: '6f9619ff-8b86-4d01-b42d-00cf4fc964ff',
    email: 'tester@parkio.dev',
    status: 'ACTIVE',
    roles,
  };
  runtime.authStore.getState().setSession('test-access-token', user);
  return user;
}

/** Switch the shared test i18n instance (tests default to English in setup). */
export async function withLocale(locale: ParkioLocale) {
  await i18n.changeLanguage(locale);
}
