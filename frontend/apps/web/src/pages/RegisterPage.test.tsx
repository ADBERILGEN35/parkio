import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it } from 'vitest';
import { useAuthStore } from '@/auth/store';
import { API_BASE, apiErrorBody, server } from '@/test/server';
import { renderWithProviders, resetAuth } from '@/test/utils';
import { AccountPreparingPage } from './AccountPreparingPage';
import { RegisterPage } from './RegisterPage';

const newcomer = {
  id: '6f9619ff-8b86-4d01-b42d-00cf4fc964ff',
  email: 'newcomer@parkio.dev',
  status: 'ACTIVE',
  roles: ['USER'],
};

const authResponse = {
  accessToken: 'access-1',
  tokenType: 'Bearer',
  accessTokenExpiresAt: '2026-06-11T11:00:00Z',
  refreshToken: 'refresh-1',
  refreshTokenExpiresAt: '2026-07-11T10:00:00Z',
  user: newcomer,
};

function renderRegister() {
  return renderWithProviders(
    <Routes>
      <Route path="/register" element={<RegisterPage />} />
      <Route path="/preparing" element={<AccountPreparingPage />} />
      <Route path="/map" element={<div>Map page stub</div>} />
    </Routes>,
    { initialEntries: ['/register'] },
  );
}

async function fillAndSubmit(email: string, password: string) {
  const user = userEvent.setup();
  await user.type(screen.getByLabelText('Email'), email);
  await user.type(screen.getByLabelText('Password'), password);
  await user.click(screen.getByRole('button', { name: 'Create account' }));
}

describe('RegisterPage', () => {
  beforeEach(() => resetAuth());

  it('stores the session and forwards to /map once the profile is ready', async () => {
    server.use(
      http.post(`${API_BASE}/auth/register`, () => HttpResponse.json(authResponse)),
      http.get(`${API_BASE}/auth/me`, () => HttpResponse.json(newcomer)),
    );

    renderRegister();
    await fillAndSubmit('newcomer@parkio.dev', 'password-1');

    expect(await screen.findByText('Map page stub')).toBeInTheDocument();
    const state = useAuthStore.getState();
    expect(state.isAuthenticated).toBe(true);
    expect(state.user?.email).toBe('newcomer@parkio.dev');
    expect(state.provisioning).toBe(false);
  });

  it('enters the preparing state (not suspended) while the profile provisions', async () => {
    server.use(
      http.post(`${API_BASE}/auth/register`, () => HttpResponse.json(authResponse)),
      // Profile not provisioned yet — protected reads 403 ACCOUNT_NOT_ACTIVE.
      http.get(`${API_BASE}/auth/me`, () =>
        HttpResponse.json(
          apiErrorBody('ACCOUNT_NOT_ACTIVE', 'Account is not active', 'trace-reg-prov'),
          { status: 403 },
        ),
      ),
    );

    renderRegister();
    await fillAndSubmit('newcomer@parkio.dev', 'password-1');

    expect(await screen.findByText('Preparing your account')).toBeInTheDocument();
    expect(screen.queryByText('Map page stub')).not.toBeInTheDocument();
    const state = useAuthStore.getState();
    expect(state.isAuthenticated).toBe(true);
    expect(state.suspended).toBe(false);
    expect(state.provisioning).toBe(true);
  });

  it('shows the backend error message with traceId on failure', async () => {
    server.use(
      http.post(`${API_BASE}/auth/register`, () =>
        HttpResponse.json(
          apiErrorBody('EMAIL_ALREADY_EXISTS', 'Email already registered', 'trace-reg-1'),
          { status: 409 },
        ),
      ),
    );

    renderRegister();
    await fillAndSubmit('taken@parkio.dev', 'password-1');

    expect(await screen.findByText('Email already registered')).toBeInTheDocument();
    expect(screen.getByText('Trace: trace-reg-1')).toBeInTheDocument();
    expect(useAuthStore.getState().isAuthenticated).toBe(false);
  });
});
