import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { I18nextProvider } from 'react-i18next';
import { RouterProvider } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { AppRuntimeProvider } from '@/app/AppRuntimeProvider';
import i18n from '@/i18n';
import { API_BASE, server } from '@/test/server';
import { DisposeTestRuntime } from '@/test/DisposeTestRuntime';
import {
  createTestAppRuntimeWithAppRouter,
  signInAs,
} from '@/test/utils';

// The authenticated root redirect lands on /map; the real MapPage pulls in
 // maplibre, which jsdom cannot render. The stub keeps this a routing test.
vi.mock('@/pages/MapPage', () => ({
  MapPage: () => <div>Map page stub</div>,
}));
vi.mock('@/pages/PublicExplorePage', () => ({
  PublicExplorePage: () => <div>Public explore page stub</div>,
}));

function renderAt(path: string, roles?: string[]) {
  const runtime = createTestAppRuntimeWithAppRouter(undefined, [path]);
  if (roles) signInAs(runtime, roles);
  render(
    <I18nextProvider i18n={i18n}>
      <AppRuntimeProvider runtime={runtime}>
        <DisposeTestRuntime runtime={runtime} />
        <QueryClientProvider client={runtime.queryClient}>
          <RouterProvider router={runtime.router} />
        </QueryClientProvider>
      </AppRuntimeProvider>
    </I18nextProvider>,
  );
  return runtime.router;
}

describe('default route (/)', () => {
  it('sends unauthenticated visitors to the login entry experience', async () => {
    const router = renderAt('/');

    expect(
      await screen.findByRole('heading', { name: 'Welcome back' }),
    ).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/login');
  });

  it('sends authenticated users to the map product home', async () => {
    // The AppShell's unread badge polls notifications as soon as /map renders.
    server.use(http.get(`${API_BASE}/notifications/me`, () => HttpResponse.json([])));
    const router = renderAt('/', ['USER']);

    expect(await screen.findByText('Map page stub')).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/map');
  });

  it('keeps registration reachable from the login entry', async () => {
    renderAt('/');

    const registerLink = await screen.findByRole('link', { name: 'Register' });
    expect(registerLink).toHaveAttribute('href', '/register');
  });
});

describe('public explore route', () => {
  it('is independently reachable without authentication and does not redirect to login', async () => {
    const router = renderAt('/explore');

    expect(await screen.findByText('Public explore page stub')).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/explore');
    expect(screen.queryByRole('heading', { name: 'Welcome back' })).not.toBeInTheDocument();
  });
});
