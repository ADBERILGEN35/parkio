import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import { API_BASE, server } from '@/test/server';
import { renderWithProviders, resetAuth, signInAs } from '@/test/utils';
import { AppNav } from './AppNav';

function useNavHandlers() {
  server.use(http.get(`${API_BASE}/notifications/me`, () => HttpResponse.json([])));
}

describe('AppNav', () => {
  beforeEach(() => {
    resetAuth();
    signInAs(['USER']);
  });

  it('toggles the mobile menu and reveals the stacked links', async () => {
    useNavHandlers();
    renderWithProviders(<AppNav />, { initialEntries: ['/map'] });
    const user = userEvent.setup();

    const toggle = screen.getByRole('button', { name: 'Open menu' });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    // Only the (CSS-hidden) desktop link is in the DOM before opening.
    expect(screen.getAllByRole('link', { name: 'Map' })).toHaveLength(1);

    await user.click(toggle);

    expect(screen.getByRole('button', { name: 'Close menu' })).toHaveAttribute(
      'aria-expanded',
      'true',
    );
    // The mobile menu adds a second copy of each link.
    expect(screen.getAllByRole('link', { name: 'Map' })).toHaveLength(2);
  });

  it('hides privileged links for non-privileged users', () => {
    useNavHandlers();
    renderWithProviders(<AppNav />, { initialEntries: ['/map'] });

    expect(screen.queryByRole('link', { name: 'Moderation' })).not.toBeInTheDocument();
  });

  it('shows privileged links for moderators', async () => {
    useNavHandlers();
    resetAuth();
    signInAs(['MODERATOR']);
    renderWithProviders(<AppNav />, { initialEntries: ['/map'] });
    const user = userEvent.setup();

    await user.click(screen.getByRole('button', { name: 'Open menu' }));

    expect(screen.getAllByRole('link', { name: 'Moderation' }).length).toBeGreaterThan(0);
  });
});
