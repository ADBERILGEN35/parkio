import { http, HttpResponse } from 'msw';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { API_BASE, server } from '@/test/server';
import { renderWithProviders, withLocale } from '@/test/utils';
import { CheckEmailPage } from './CheckEmailPage';

const CONDITIONAL_RESENT_EN =
  'If this address still needs verification, check your inbox for a link. If you already verified, sign in instead.';
const CONDITIONAL_RESENT_TR =
  'Bu adres hâlâ doğrulanmayı bekliyorsa gelen kutunuzdaki bağlantıyı kontrol edin. Zaten doğruladıysanız giriş yapın.';

function renderCheckEmail(
  entry: string | { pathname: string; state?: unknown } = {
    pathname: '/check-email',
    state: { email: 'verified@parkio.dev' },
  },
) {
  return renderWithProviders(
    <Routes>
      <Route path="/check-email" element={<CheckEmailPage />} />
      <Route path="/login" element={<div>Login stub</div>} />
    </Routes>,
    { initialEntries: [entry] },
  );
}

describe('CheckEmailPage resend feedback', () => {
  it('shows enumeration-safe conditional copy after accepted resend (EN)', async () => {
    let calls = 0;
    server.use(
      http.post(`${API_BASE}/auth/resend-verification`, async () => {
        calls += 1;
        return new HttpResponse(null, { status: 202 });
      }),
    );

    await withLocale('en');
    renderCheckEmail();
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Resend verification' }));

    await waitFor(() => expect(calls).toBe(1));
    expect(screen.getByText(CONDITIONAL_RESENT_EN)).toBeInTheDocument();
    expect(screen.queryByText(/Verification email sent/i)).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Sign in' })).toHaveAttribute('href', '/login');
  });

  it('shows enumeration-safe conditional copy after accepted resend (TR)', async () => {
    server.use(
      http.post(`${API_BASE}/auth/resend-verification`, () => new HttpResponse(null, { status: 202 })),
    );

    await withLocale('tr');
    renderCheckEmail({ pathname: '/check-email', state: { email: 'verified@parkio.dev' } });
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Doğrulamayı yeniden gönder' }));

    expect(await screen.findByText(CONDITIONAL_RESENT_TR)).toBeInTheDocument();
    expect(screen.queryByText(/gönderildi/i)).not.toBeInTheDocument();
  });
});

describe('CheckEmailPage address handling (CL-F39.1)', () => {
  it('prefills the address passed in navigation state', async () => {
    await withLocale('en');
    renderCheckEmail({ pathname: '/check-email', state: { email: 'state@parkio.dev' } });

    expect(await screen.findByLabelText('Email')).toHaveValue('state@parkio.dev');
  });

  it('does not take an address from the query string', async () => {
    await withLocale('en');
    renderCheckEmail('/check-email?email=leak@parkio.dev');

    expect(await screen.findByLabelText('Email')).toHaveValue('');
  });

  it('starts empty on a direct visit and resends only after an address is typed', async () => {
    let body: Record<string, unknown> | null = null;
    server.use(
      http.post(`${API_BASE}/auth/resend-verification`, async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return new HttpResponse(null, { status: 202 });
      }),
    );

    await withLocale('en');
    renderCheckEmail('/check-email');
    const user = userEvent.setup();
    const button = await screen.findByRole('button', { name: 'Resend verification' });

    expect(screen.getByLabelText('Email')).toHaveValue('');
    expect(button).toBeDisabled();
    await user.type(screen.getByLabelText('Email'), 'typed@parkio.dev');
    await user.click(button);

    await waitFor(() => expect(body).toEqual({ email: 'typed@parkio.dev', locale: 'en' }));
  });
});
