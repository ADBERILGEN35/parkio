import { http, HttpResponse } from 'msw';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it } from 'vitest';
import { AppShell } from '@/components/shell/AppShell';
import { API_BASE, server } from '@/test/server';
import { renderWithProviders, resetAuth, signInAs } from '@/test/utils';

function useNavHandlers() {
  server.use(http.get(`${API_BASE}/notifications/me`, () => HttpResponse.json([])));
}

function renderShell(initialEntry = '/map') {
  return renderWithProviders(
    <Routes>
      <Route element={<AppShell />}>
        <Route path="/map" element={<div>Map page stub</div>} />
        <Route path="/moderation" element={<div>Moderation dashboard stub</div>} />
      </Route>
    </Routes>,
    { initialEntries: [initialEntry] },
  );
}

describe('AppShell navigation', () => {
  beforeEach(() => {
    resetAuth();
    signInAs(['USER']);
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

  it('opens the mobile overflow menu and reveals secondary links', async () => {
    useNavHandlers();
    renderShell();
    const user = userEvent.setup();

    await user.click(screen.getByRole('button', { name: 'More' }));

    const morePanel = document.getElementById('mobile-nav-more');
    expect(morePanel).not.toBeNull();
    expect(within(morePanel!).getByRole('link', { name: 'Notifications' })).toBeInTheDocument();
    expect(within(morePanel!).queryByRole('link', { name: 'Moderation' })).not.toBeInTheDocument();
  });

  it('shows privileged links in the mobile overflow menu for moderators', async () => {
    useNavHandlers();
    resetAuth();
    signInAs(['MODERATOR']);
    renderShell();
    const user = userEvent.setup();

    await user.click(screen.getByRole('button', { name: 'More' }));

    const morePanel = document.getElementById('mobile-nav-more');
    expect(morePanel).not.toBeNull();
    expect(within(morePanel!).getByRole('link', { name: 'Moderation' })).toBeInTheDocument();
  });
});
