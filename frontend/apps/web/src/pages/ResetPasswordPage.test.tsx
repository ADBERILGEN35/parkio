import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it } from 'vitest';
import { useAuthStore } from '@/auth/store';
import { API_BASE, server } from '@/test/server';
import { renderWithProviders, resetAuth, signInAs } from '@/test/utils';
import { ResetPasswordPage } from './ResetPasswordPage';

function renderPage() {
  return renderWithProviders(
    <Routes>
      <Route path="/reset-password" element={<ResetPasswordPage />} />
      <Route path="/login" element={<div>Login page</div>} />
    </Routes>,
    { initialEntries: ['/reset-password?token=reset-token-1'] },
  );
}

describe('ResetPasswordPage', () => {
  beforeEach(() => resetAuth());

  it('validates confirmation before submitting', async () => {
    renderPage();
    const user = userEvent.setup();

    await user.type(screen.getByLabelText('New password'), 'FreshStrong123');
    await user.type(screen.getByLabelText('Confirm password'), 'FreshStrong124');
    await user.click(screen.getByRole('button', { name: /Reset password/ }));

    expect(await screen.findByText('Passwords do not match')).toBeInTheDocument();
  });

  it('resets password and redirects to login', async () => {
    signInAs(['USER']);
    let body: { token: string; newPassword: string } | null = null;
    server.use(
      http.post(`${API_BASE}/auth/reset-password`, async ({ request }) => {
        body = (await request.json()) as { token: string; newPassword: string };
        return new HttpResponse(null, { status: 204 });
      }),
    );

    renderPage();
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('New password'), 'FreshStrong123');
    await user.type(screen.getByLabelText('Confirm password'), 'FreshStrong123');
    await user.click(screen.getByRole('button', { name: /Reset password/ }));

    expect(await screen.findByText('Login page')).toBeInTheDocument();
    expect(body).toEqual({ token: 'reset-token-1', newPassword: 'FreshStrong123' });
    expect(useAuthStore.getState().isAuthenticated).toBe(false);
  });
});
