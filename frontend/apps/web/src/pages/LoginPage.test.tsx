import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it } from 'vitest';
import { useAuthStore } from '@/auth/store';
import { setPendingProfile } from '@/auth/pendingProfile';
import { API_BASE, apiErrorBody, server } from '@/test/server';
import { renderWithProviders, resetAuth } from '@/test/utils';
import { AccountPreparingPage } from './AccountPreparingPage';
import { LoginPage } from './LoginPage';

const authResponse = {
  accessToken: 'access-1',
  tokenType: 'Bearer',
  accessTokenExpiresAt: '2026-06-11T11:00:00Z',
  refreshTokenExpiresAt: '2026-07-11T10:00:00Z',
  user: {
    id: '6f9619ff-8b86-4d01-b42d-00cf4fc964ff',
    email: 'tester@parkio.dev',
    status: 'ACTIVE',
    roles: ['USER'],
  },
};

function renderLogin() {
  return renderWithProviders(
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/preparing" element={<AccountPreparingPage />} />
      <Route path="/map" element={<div>Map page stub</div>} />
    </Routes>,
    { initialEntries: ['/login'] },
  );
}

async function fillAndSubmit(email: string, password: string) {
  const user = userEvent.setup();
  await user.type(screen.getByLabelText('Email'), email);
  await user.type(screen.getByLabelText('Password'), password);
  await user.click(screen.getByRole('button', { name: 'Sign in' }));
}

describe('LoginPage', () => {
  beforeEach(() => resetAuth());

  it('stores the session and redirects to /map on success', async () => {
    server.use(http.post(`${API_BASE}/auth/login`, () => HttpResponse.json(authResponse)));

    renderLogin();
    await fillAndSubmit('tester@parkio.dev', 'password-1');

    expect(await screen.findByText('Map page stub')).toBeInTheDocument();
    const state = useAuthStore.getState();
    expect(state.isAuthenticated).toBe(true);
    expect(state.user?.email).toBe('tester@parkio.dev');
    expect(state.accessToken).toBe('access-1');
    expect(localStorage.getItem('parkio.accessToken')).toBeNull();
    expect(localStorage.getItem('parkio.refreshToken')).toBeNull();
  });

  it('does not render a no-op remember-me control', () => {
    renderLogin();

    expect(screen.queryByRole('checkbox', { name: /remember me/i })).not.toBeInTheDocument();
    expect(screen.getByText(/HttpOnly refresh cookie/)).toBeInTheDocument();
  });

  it('shows a friendly error with traceId on invalid credentials', async () => {
    server.use(
      http.post(`${API_BASE}/auth/login`, () =>
        HttpResponse.json(apiErrorBody('INVALID_CREDENTIALS', 'Bad credentials', 'trace-login-1'), {
          status: 401,
        }),
      ),
    );

    renderLogin();
    await fillAndSubmit('tester@parkio.dev', 'wrong-password');

    expect(await screen.findByText('Invalid email or password.')).toBeInTheDocument();
    expect(screen.getByText('Trace: trace-login-1')).toBeInTheDocument();
    expect(useAuthStore.getState().isAuthenticated).toBe(false);
  });

  it('shows a friendly message when the account is not verified', async () => {
    server.use(
      http.post(`${API_BASE}/auth/login`, () =>
        HttpResponse.json(
          apiErrorBody('ACCOUNT_NOT_VERIFIED', 'Please verify your email before signing in.', 'trace-verify-1'),
          { status: 403 },
        ),
      ),
    );

    renderLogin();
    await fillAndSubmit('tester@parkio.dev', 'password-1');

    expect(await screen.findByText('Please verify your email before signing in.')).toBeInTheDocument();
    expect(screen.getByText('Trace: trace-verify-1')).toBeInTheDocument();
    expect(useAuthStore.getState().isAuthenticated).toBe(false);
  });

  it('resumes profile provisioning after verified login when pending profile data exists', async () => {
    setPendingProfile({ displayName: 'New Driver', phoneNumber: '5551234567' });
    server.use(
      http.post(`${API_BASE}/auth/login`, () => HttpResponse.json(authResponse)),
      http.get(`${API_BASE}/auth/me`, () => HttpResponse.json(authResponse.user)),
      http.patch(`${API_BASE}/users/me`, () => HttpResponse.json({})),
    );

    renderLogin();
    await fillAndSubmit('tester@parkio.dev', 'password-1');

    expect(await screen.findByText('Map page stub')).toBeInTheDocument();
    expect(useAuthStore.getState().isAuthenticated).toBe(true);
  });
});
