import { http, HttpResponse } from 'msw';
import { configure, fireEvent, screen, waitFor } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { useLocaleStore } from '@/i18n/localeStore';
import { API_BASE, apiErrorBody, server } from '@/test/server';
import { createTestAppRuntime, createTestQueryClient, renderWithProviders } from '@/test/utils';
import { VerifyEmailPage } from './VerifyEmailPage';

const INVALID_LINK_EN =
  'This verification link is invalid, has expired or was already used. If you already verified your email, sign in. Otherwise, request a new link.';
const INVALID_LINK_TR =
  'Bu doğrulama bağlantısı geçersiz, süresi dolmuş veya daha önce kullanılmış. E-postanızı zaten doğruladıysanız giriş yapın; doğrulamadıysanız yeni bir bağlantı isteyin.';

const verifiedUser = {
  id: '6f9619ff-8b86-4d01-b42d-00cf4fc964ff',
  email: 'newcomer@parkio.dev',
  status: 'ACTIVE',
  roles: ['USER'],
};

function renderVerify(query: string) {
  const initialEntries = [`/verify-email${query}`];
  const runtime = createTestAppRuntime(createTestQueryClient(), initialEntries);
  // Counts calls when the page makes them, not when a request reaches the mock server.
  const verifyEmail = vi.spyOn(runtime.sdk.authApi, 'verifyEmail');
  const routes = (
    <Routes>
      <Route path="/verify-email" element={<VerifyEmailPage />} />
      <Route path="/login" element={<div>Login page stub</div>} />
      <Route path="/check-email" element={<div>Check email stub</div>} />
    </Routes>
  );
  renderWithProviders(routes, { initialEntries, runtime });
  return { verifyEmail };
}

/** The server spends a verification token on first use (CL-F35): later calls are rejected. */
function singleUseVerifyHandler() {
  let served = 0;
  server.use(
    http.post(`${API_BASE}/auth/verify-email`, () => {
      served += 1;
      return served === 1
        ? HttpResponse.json(verifiedUser)
        : HttpResponse.json(
            apiErrorBody('INVALID_VERIFICATION_TOKEN', 'Email verification token is invalid or expired.'),
            { status: 400 },
          );
    }),
  );
}

describe('VerifyEmailPage', () => {
  it('shows success when the token verifies', async () => {
    let body: Record<string, unknown> | null = null;
    server.use(
      http.post(`${API_BASE}/auth/verify-email`, async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json(verifiedUser);
      }),
    );

    renderVerify('?token=valid-token');

    expect(await screen.findByText('Email verified')).toBeInTheDocument();
    expect(screen.getByText('Your account is ready for sign in.')).toBeInTheDocument();
    expect(body).toEqual({ token: 'valid-token' });
  });

  it('applies lang from the verification link before rendering copy', async () => {
    useLocaleStore.getState().setLocale('en');
    server.use(
      http.post(`${API_BASE}/auth/verify-email`, () => HttpResponse.json(verifiedUser)),
    );

    renderVerify('?token=valid-token&lang=tr');

    await waitFor(() => expect(useLocaleStore.getState().locale).toBe('tr'));
    expect(await screen.findByText('E-posta doğrulandı')).toBeInTheDocument();
  });

  it('sends the token once when the link switches the language', async () => {
    useLocaleStore.getState().setLocale('en');
    singleUseVerifyHandler();

    const { verifyEmail } = renderVerify('?token=valid-token&lang=tr');

    expect(await screen.findByText('E-posta doğrulandı')).toBeInTheDocument();
    expect(verifyEmail).toHaveBeenCalledTimes(1);
  });

  it('sends the token once when StrictMode re-runs effects', async () => {
    singleUseVerifyHandler();
    // The app renders under a root-level StrictMode (main.tsx). Only a root-level one re-runs
    // effects here; a StrictMode nested inside the test router does not.
    configure({ reactStrictMode: true });
    try {
      const { verifyEmail } = renderVerify('?token=valid-token');

      expect(await screen.findByText('Email verified')).toBeInTheDocument();
      expect(verifyEmail).toHaveBeenCalledTimes(1);
    } finally {
      configure({ reactStrictMode: false });
    }
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

    renderVerify('?token=expired-token');

    expect(await screen.findAllByText(INVALID_LINK_EN)).not.toHaveLength(0);
    expect(screen.queryByText('Email verification token is invalid or expired.')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Request a new link' })).toBeInTheDocument();
  });

  it('offers sign-in for a link that was already used, in the link language', async () => {
    useLocaleStore.getState().setLocale('en');
    server.use(
      http.post(`${API_BASE}/auth/verify-email`, () =>
        HttpResponse.json(
          apiErrorBody('INVALID_VERIFICATION_TOKEN', 'Email verification token is invalid or expired.'),
          { status: 400 },
        ),
      ),
    );

    renderVerify('?token=spent-token&lang=tr');

    expect(await screen.findAllByText(INVALID_LINK_TR)).not.toHaveLength(0);
    fireEvent.click(screen.getByRole('button', { name: 'Giriş yap' }));
    expect(await screen.findByText('Login page stub')).toBeInTheDocument();
  });

  it('shows failure when token is missing', async () => {
    renderVerify('');

    expect(await screen.findByText(INVALID_LINK_EN)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeInTheDocument();
  });
});
