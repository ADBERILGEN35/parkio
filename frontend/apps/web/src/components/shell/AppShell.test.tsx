import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it } from 'vitest';
import type { WebAppRuntime } from '@/app/runtime';
import { AppShell } from '@/components/shell/AppShell';
import { API_BASE, server } from '@/test/server';
import {
  createTestAppRuntime,
  renderWithProviders as renderWithBaseProviders,
  resetAuth,
  signInAs,
} from '@/test/utils';

let runtime: WebAppRuntime;

function useNavHandlers() {
  server.use(http.get(`${API_BASE}/notifications/me`, () => HttpResponse.json([])));
}

function renderShell(initialEntry = '/map') {
  return renderWithBaseProviders(
    <Routes>
      <Route element={<AppShell />}>
        <Route path="/map" element={<div>Map page stub</div>} />
        <Route path="/upload" element={<div>Upload page stub</div>} />
        <Route path="/moderation" element={<div>Moderation dashboard stub</div>} />
      </Route>
    </Routes>,
    { initialEntries: [initialEntry], runtime },
  );
}

describe('AppShell navigation', () => {
  beforeEach(() => {
    runtime = createTestAppRuntime();
    signInAs(runtime, ['USER']);
  });

  it('renders persistent desktop navigation with the Parkio brand', () => {
    useNavHandlers();
    renderShell();
    const home = screen.getByRole('link', { name: 'Parkio home' });
    const desktopHeader = home.closest('header');
    expect(desktopHeader).not.toBeNull();
    expect(within(desktopHeader!).getByRole('link', { name: 'Map' })).toBeInTheDocument();
    expect(screen.getByRole('navigation', { name: 'Primary' })).toBeInTheDocument();
  });

  it('preserves PUSH semantics for explicit user navigation', async () => {
    useNavHandlers();
    const { router } = renderShell();
    const user = userEvent.setup();
    const desktopHeader = screen
      .getByRole('link', { name: 'Parkio home' })
      .closest('header');

    expect(desktopHeader).not.toBeNull();
    await user.click(
      within(desktopHeader!).getByRole('link', {
        name: 'Share a spot',
      }),
    );

    await waitFor(() =>
      expect(router.state.location.pathname).toBe('/upload'),
    );
    expect(router.state.historyAction).toBe('PUSH');
  });

  it('uses breakpoint-aware shell padding (no desktop-header reserve on mobile base classes)', () => {
    useNavHandlers();
    renderShell();
    const main = document.querySelector('main');
    expect(main).not.toBeNull();
    expect(main!.className).toMatch(/\bpt-desktop-nav\b/);
    expect(main!.className).toMatch(/\bpb-mobile-nav\b/);
    // Legacy pt-16 on main reserved space for a hidden desktop header on mobile.
    expect(main!.className).not.toMatch(/\bpt-16\b/);
    expect(main!.className).not.toMatch(/\bpb-16\b/);
  });

  it('opens the mobile overflow menu and reveals secondary links', async () => {
    useNavHandlers();
    renderShell();
    const user = userEvent.setup();

    await user.click(screen.getByRole('button', { name: 'More' }));

    const morePanel = document.getElementById('mobile-nav-more');
    expect(morePanel).not.toBeNull();
    expect(within(morePanel!).getByRole('link', { name: 'Notifications' })).toBeInTheDocument();
    // Gamification is surfaced as "Impact" (not "Progress") in the nav.
    expect(within(morePanel!).getByRole('link', { name: 'Impact' })).toBeInTheDocument();
    expect(within(morePanel!).queryByRole('link', { name: 'Moderation' })).not.toBeInTheDocument();
  });

  it('shows privileged links in the mobile overflow menu for moderators', async () => {
    useNavHandlers();
    resetAuth(runtime);
    signInAs(runtime, ['MODERATOR']);
    renderShell();
    const user = userEvent.setup();

    await user.click(screen.getByRole('button', { name: 'More' }));

    const morePanel = document.getElementById('mobile-nav-more');
    expect(morePanel).not.toBeNull();
    expect(within(morePanel!).getByRole('link', { name: 'Moderation' })).toBeInTheDocument();
    expect(within(morePanel!).queryByRole('link', { name: 'Analytics' })).not.toBeInTheDocument();
  });

  it('shows moderation and admin in the mobile overflow menu for admins', async () => {
    useNavHandlers();
    resetAuth(runtime);
    signInAs(runtime, ['ADMIN']);
    renderShell();
    const user = userEvent.setup();

    await user.click(screen.getByRole('button', { name: 'More' }));

    const morePanel = document.getElementById('mobile-nav-more');
    expect(morePanel).not.toBeNull();
    expect(within(morePanel!).getByRole('link', { name: 'Moderation' })).toBeInTheDocument();
    expect(within(morePanel!).getByRole('link', { name: 'Admin' })).toBeInTheDocument();
  });

  it('shows admin in desktop navigation for admins only', () => {
    useNavHandlers();
    resetAuth(runtime);
    signInAs(runtime, ['ADMIN']);
    renderShell();

    const home = screen.getByRole('link', { name: 'Parkio home' });
    const desktopHeader = home.closest('header');
    expect(desktopHeader).not.toBeNull();
    expect(within(desktopHeader!).getByRole('link', { name: 'Admin' })).toBeInTheDocument();
  });

  it('hides analytics in desktop navigation for moderators', () => {
    useNavHandlers();
    resetAuth(runtime);
    signInAs(runtime, ['MODERATOR']);
    renderShell();

    const home = screen.getByRole('link', { name: 'Parkio home' });
    const desktopHeader = home.closest('header');
    expect(desktopHeader).not.toBeNull();
    expect(within(desktopHeader!).getByRole('link', { name: 'Moderation' })).toBeInTheDocument();
    expect(within(desktopHeader!).queryByRole('link', { name: 'Analytics' })).not.toBeInTheDocument();
  });
});
