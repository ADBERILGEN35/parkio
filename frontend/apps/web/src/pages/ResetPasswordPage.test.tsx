import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { useLocaleStore } from '@/i18n/localeStore';
import { API_BASE, server } from '@/test/server';
import { renderWithProviders } from '@/test/utils';
import { ResetPasswordPage } from './ResetPasswordPage';

function renderPage(authRoles?: string[], entry = '/reset-password?token=reset-token-1') {
  return renderWithProviders(
    <Routes>
      <Route path="/reset-password" element={<ResetPasswordPage />} />
      <Route path="/login" element={<div>Login page</div>} />
    </Routes>,
    { authRoles, initialEntries: [entry] },
  );
}

describe('ResetPasswordPage', () => {
  it('renders in the language of the reset link over a stored preference (CL-F21)', async () => {
    useLocaleStore.getState().setLocale('tr');

    renderPage(undefined, '/reset-password?token=reset-token-1&lang=en');

    expect(await screen.findByRole('heading', { name: 'Choose a new password' })).toBeInTheDocument();
    expect(useLocaleStore.getState().locale).toBe('en');
    expect(document.documentElement.lang).toBe('en');
  });

  it('validates confirmation before submitting', async () => {
    renderPage();
    const user = userEvent.setup();

    await user.type(screen.getByLabelText('New password'), 'FreshStrong123');
    await user.type(screen.getByLabelText('Confirm password'), 'FreshStrong124');
    await user.click(screen.getByRole('button', { name: /Reset password/ }));

    expect(await screen.findByText('Passwords do not match')).toBeInTheDocument();
  });

  it('resets password and redirects to login', async () => {
    let body: { token: string; newPassword: string } | null = null;
    server.use(
      http.post(`${API_BASE}/auth/reset-password`, async ({ request }) => {
        body = (await request.json()) as { token: string; newPassword: string };
        return new HttpResponse(null, { status: 204 });
      }),
    );

    const { runtime } = renderPage(['USER']);
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('New password'), 'FreshStrong123');
    await user.type(screen.getByLabelText('Confirm password'), 'FreshStrong123');
    await user.click(screen.getByRole('button', { name: /Reset password/ }));

    expect(await screen.findByText('Login page')).toBeInTheDocument();
    expect(body).toEqual({ token: 'reset-token-1', newPassword: 'FreshStrong123' });
    expect(runtime.authStore.getState().isAuthenticated).toBe(false);
  });
});
