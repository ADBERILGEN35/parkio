import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { I18nextProvider } from 'react-i18next';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import i18n from '@/i18n';
import { API_BASE, server } from '@/test/server';
import { createTestQueryClient, resetAuth, signInAs } from '@/test/utils';
import { routes } from './router';

// The authenticated root redirect lands on /map; the real MapPage pulls in
 // maplibre, which jsdom cannot render. The stub keeps this a routing test.
vi.mock('@/pages/MapPage', () => ({
  MapPage: () => <div>Map page stub</div>,
}));

function renderAt(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  render(
    <I18nextProvider i18n={i18n}>
      <QueryClientProvider client={createTestQueryClient()}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </I18nextProvider>,
  );
  return router;
}

describe('default route (/)', () => {
  beforeEach(() => resetAuth());

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
    signInAs(['USER']);
    const router = renderAt('/');

    expect(await screen.findByText('Map page stub')).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/map');
  });

  it('keeps registration reachable from the login entry', async () => {
    renderAt('/');

    const registerLink = await screen.findByRole('link', { name: 'Register' });
    expect(registerLink).toHaveAttribute('href', '/register');
  });
});
