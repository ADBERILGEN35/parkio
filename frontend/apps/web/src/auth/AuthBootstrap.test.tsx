import { setRefreshHandler } from '@parkio/api-client';
import { render, waitFor } from '@testing-library/react';
import { StrictMode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AppRuntimeProvider } from '@/app/AppRuntimeProvider';
import { createWebAppRuntime, type WebAppRuntime } from '@/app/runtime';
import { createMemoryAppRouter } from '@/routing/create-app-router';
import {
  ROUTE_MANIFEST,
  buildRoutePath,
  type RouteManifestEntry,
} from '@/routing/route-manifest';
import { DisposeTestRuntime } from '@/test/DisposeTestRuntime';
import { AuthBootstrap } from './AuthBootstrap';

const user = {
  id: '6f9619ff-8b86-4d01-b42d-00cf4fc964ff',
  email: 'tester@parkio.dev',
  status: 'ACTIVE',
  roles: ['USER'],
};

const VALID_UUID = '6f9619ff-8b86-4d01-b42d-00cf4fc964ff';

function addressablePath(entry: RouteManifestEntry): string {
  if (entry.kind === 'path' && entry.path === '*') {
    return '/not-found-from-bootstrap-test';
  }
  if (entry.kind === 'path' || entry.kind === 'index') {
    return buildRoutePath(
      entry.id,
      Object.fromEntries(
        entry.parameters.map((parameter) => [
          parameter.name,
          VALID_UUID,
        ]),
      ),
    );
  }
  throw new Error(`Route '${entry.id}' is not addressable.`);
}

const PUBLIC_ENTRY_CASES = ROUTE_MANIFEST.filter(
  (entry) =>
    (entry.kind === 'path' || entry.kind === 'index') &&
    entry.bootstrap === 'public-immediate',
).map((entry) => [entry.id, addressablePath(entry)] as const);

const PROTECTED_ENTRY_CASES = ROUTE_MANIFEST.filter(
  (entry) =>
    (entry.kind === 'path' || entry.kind === 'index') &&
    entry.bootstrap === 'protected-await',
).map((entry) => [entry.id, addressablePath(entry)] as const);

function createRuntime(
  pathname = '/map',
  browserPathname = pathname,
) {
  window.history.pushState({}, '', browserPathname);
  return createWebAppRuntime({
    baseURL: 'http://api.test/api/v1',
    createRouter: () =>
      createMemoryAppRouter({ initialEntries: [pathname] }),
  });
}

function renderBootstrap(activeRuntime: WebAppRuntime) {
  return render(
    <StrictMode>
      <AppRuntimeProvider runtime={activeRuntime}>
        <DisposeTestRuntime runtime={activeRuntime} />
        <AuthBootstrap />
      </AppRuntimeProvider>
    </StrictMode>,
  );
}

afterEach(() => {
  setRefreshHandler(null);
});

describe('AuthBootstrap', () => {
  it('uses one SDK refresh under Strict Mode double invocation', async () => {
    const activeRuntime = createRuntime();
    const refresh = vi.fn(async () => 'access-token');
    setRefreshHandler(refresh);

    renderBootstrap(activeRuntime);

    await waitFor(() => expect(activeRuntime.authStore.getState().bootstrapPending).toBe(false));
    expect(refresh).toHaveBeenCalledTimes(1);
  });

  it('does not refresh when a session already exists', async () => {
    const activeRuntime = createRuntime();
    activeRuntime.authStore.getState().setSession('existing', user);
    const refresh = vi.fn(async () => 'unexpected');
    setRefreshHandler(refresh);

    renderBootstrap(activeRuntime);

    await waitFor(() => expect(activeRuntime.authStore.getState().bootstrapPending).toBe(false));
    expect(refresh).not.toHaveBeenCalled();
  });

  it.each(PUBLIC_ENTRY_CASES)(
    'settles manifest-owned public entry %s without session restoration',
    async (_routeId, pathname) => {
      const activeRuntime = createRuntime(pathname);
      const refresh = vi.fn(async () => 'unexpected');
      setRefreshHandler(refresh);

      renderBootstrap(activeRuntime);

      await waitFor(() =>
        expect(activeRuntime.authStore.getState().lifecycle).toBe(
          'anonymous',
        ),
      );
      expect(refresh).not.toHaveBeenCalled();
    },
  );

  it.each(PROTECTED_ENTRY_CASES)(
    'restores manifest-owned protected entry %s through the SDK',
    async (_routeId, pathname) => {
      const activeRuntime = createRuntime(pathname);
      const refresh = vi.fn(async () => 'access-token');
      setRefreshHandler(refresh);

      renderBootstrap(activeRuntime);

      await waitFor(() =>
        expect(
          activeRuntime.authStore.getState().bootstrapPending,
        ).toBe(false),
      );
      expect(refresh).toHaveBeenCalledTimes(1);
    },
  );

  it('classifies bootstrap from the runtime router instead of the browser global', async () => {
    const activeRuntime = createRuntime('/privacy', '/map');
    const refresh = vi.fn(async () => 'unexpected');
    setRefreshHandler(refresh);

    renderBootstrap(activeRuntime);

    await waitFor(() =>
      expect(activeRuntime.authStore.getState().lifecycle).toBe(
        'anonymous',
      ),
    );
    expect(refresh).not.toHaveBeenCalled();
  });

  it('always settles when SDK restoration resolves without a session', async () => {
    const activeRuntime = createRuntime();
    setRefreshHandler(vi.fn(async () => null));

    renderBootstrap(activeRuntime);

    await waitFor(() => expect(activeRuntime.authStore.getState().lifecycle).toBe('anonymous'));
    expect(activeRuntime.authStore.getState().bootstrapPending).toBe(false);
  });
});
