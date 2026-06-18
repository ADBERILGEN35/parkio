import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it } from 'vitest';
import { API_BASE, server } from '@/test/server';
import { renderWithProviders, resetAuth } from '@/test/utils';
import { ForgotPasswordPage } from './ForgotPasswordPage';

function renderPage() {
  return renderWithProviders(
    <Routes>
      <Route path="/forgot-password" element={<ForgotPasswordPage />} />
    </Routes>,
    { initialEntries: ['/forgot-password'] },
  );
}

describe('ForgotPasswordPage', () => {
  beforeEach(() => resetAuth());

  it('submits email and shows the generic success message', async () => {
    let requestedEmail = '';
    server.use(
      http.post(`${API_BASE}/auth/forgot-password`, async ({ request }) => {
        requestedEmail = ((await request.json()) as { email: string }).email;
        return new HttpResponse(null, { status: 202 });
      }),
    );

    renderPage();
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('Email'), 'tester@parkio.dev');
    await user.click(screen.getByRole('button', { name: /Send instructions/ }));

    expect(await screen.findByText('If an account exists, we sent password reset instructions.')).toBeInTheDocument();
    expect(requestedEmail).toBe('tester@parkio.dev');
  });
});
