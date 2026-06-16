import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it } from 'vitest';
import { API_BASE, apiErrorBody, server } from '@/test/server';
import { renderWithProviders, resetAuth } from '@/test/utils';
import { VerifyEmailPage } from './VerifyEmailPage';

const verifiedUser = {
  id: '6f9619ff-8b86-4d01-b42d-00cf4fc964ff',
  email: 'newcomer@parkio.dev',
  status: 'ACTIVE',
  roles: ['USER'],
};

function renderVerify(token = 'verify-token') {
  return renderWithProviders(
    <Routes>
      <Route path="/verify-email" element={<VerifyEmailPage />} />
      <Route path="/login" element={<div>Login page stub</div>} />
      <Route path="/check-email" element={<div>Check email stub</div>} />
    </Routes>,
    { initialEntries: [`/verify-email${token ? `?token=${token}` : ''}`] },
  );
}

describe('VerifyEmailPage', () => {
  beforeEach(() => resetAuth());

  it('shows success when the token verifies', async () => {
    let body: Record<string, unknown> | null = null;
    server.use(
      http.post(`${API_BASE}/auth/verify-email`, async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json(verifiedUser);
      }),
    );

    renderVerify('valid-token');

    expect(await screen.findByText('Email verified')).toBeInTheDocument();
    expect(screen.getByText('Your account is ready for sign in.')).toBeInTheDocument();
    expect(body).toEqual({ token: 'valid-token' });
  });

  it('shows failure when verification is rejected', async () => {
    server.use(
      http.post(`${API_BASE}/auth/verify-email`, () =>
        HttpResponse.json(
          apiErrorBody('INVALID_VERIFICATION_TOKEN', 'Email verification token is invalid or expired.'),
          { status: 400 },
        ),
      ),
    );

    renderVerify('expired-token');

    expect(
      await screen.findByText('Email verification token is invalid or expired.'),
    ).toBeInTheDocument();
  });

  it('shows failure when token is missing', async () => {
    renderVerify('');

    expect(await screen.findByText('Verification link is invalid or expired.')).toBeInTheDocument();
  });
});
