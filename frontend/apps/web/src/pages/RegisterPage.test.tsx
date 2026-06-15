import { http, HttpResponse } from 'msw';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it } from 'vitest';
import { useAuthStore } from '@/auth/store';
import { getPendingProfile } from '@/auth/pendingProfile';
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

interface FormOverrides {
  displayName?: string;
  email?: string;
  phoneNumber?: string;
  password?: string;
  confirmPassword?: string;
  acceptTerms?: boolean;
}

async function fillAndSubmit(overrides: FormOverrides = {}) {
  renderRegister();
  const user = userEvent.setup();
  const values = {
    displayName: 'New Driver',
    email: 'newcomer@parkio.dev',
    phoneNumber: '',
    password: 'password-1',
    confirmPassword: 'password-1',
    acceptTerms: true,
    ...overrides,
  };

  if (values.displayName) await user.type(screen.getByLabelText('Full name'), values.displayName);
  if (values.email) await user.type(screen.getByLabelText('Email'), values.email);
  if (values.phoneNumber) {
    await user.type(screen.getByLabelText(/Phone number/), values.phoneNumber);
  }
  if (values.password) await user.type(screen.getByLabelText('Password'), values.password);
  if (values.confirmPassword) {
    await user.type(screen.getByLabelText('Confirm password'), values.confirmPassword);
  }
  if (values.acceptTerms) {
    await user.click(screen.getByRole('checkbox', { name: /I agree/ }));
  }
  await user.click(screen.getByRole('button', { name: 'Create account' }));
  return user;
}

describe('RegisterPage', () => {
  beforeEach(() => resetAuth());

  it('requires a display name', async () => {
    let registerCalls = 0;
    server.use(
      http.post(`${API_BASE}/auth/register`, () => {
        registerCalls += 1;
        return HttpResponse.json(authResponse);
      }),
    );

    await fillAndSubmit({ displayName: '' });

    expect(await screen.findByText('Enter your name (at least 2 characters)')).toBeInTheDocument();
    expect(registerCalls).toBe(0);
    expect(useAuthStore.getState().isAuthenticated).toBe(false);
  });

  it('blocks submit when the passwords do not match', async () => {
    let registerCalls = 0;
    server.use(
      http.post(`${API_BASE}/auth/register`, () => {
        registerCalls += 1;
        return HttpResponse.json(authResponse);
      }),
    );

    await fillAndSubmit({ confirmPassword: 'different-1' });

    expect(await screen.findByText('Passwords do not match')).toBeInTheDocument();
    expect(registerCalls).toBe(0);
  });

  it('requires accepting the terms', async () => {
    let registerCalls = 0;
    server.use(
      http.post(`${API_BASE}/auth/register`, () => {
        registerCalls += 1;
        return HttpResponse.json(authResponse);
      }),
    );

    await fillAndSubmit({ acceptTerms: false });

    expect(await screen.findByText('You must accept the terms to continue')).toBeInTheDocument();
    expect(registerCalls).toBe(0);
  });

  it('sends only email and password to the register endpoint', async () => {
    let body: Record<string, unknown> | null = null;
    server.use(
      http.post(`${API_BASE}/auth/register`, async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json(authResponse);
      }),
      http.get(`${API_BASE}/auth/me`, () => HttpResponse.json(newcomer)),
      http.patch(`${API_BASE}/users/me`, () => HttpResponse.json({})),
    );

    await fillAndSubmit({ displayName: 'New Driver', phoneNumber: '5551234567' });

    await waitFor(() => expect(body).not.toBeNull());
    expect(Object.keys(body!).sort()).toEqual(['email', 'password']);
    expect(body!.email).toBe('newcomer@parkio.dev');
  });

  it('saves the captured display name and phone via PATCH after provisioning', async () => {
    let patchBody: Record<string, unknown> | null = null;
    server.use(
      http.post(`${API_BASE}/auth/register`, () => HttpResponse.json(authResponse)),
      http.get(`${API_BASE}/auth/me`, () => HttpResponse.json(newcomer)),
      http.patch(`${API_BASE}/users/me`, async ({ request }) => {
        patchBody = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({});
      }),
    );

    await fillAndSubmit({ displayName: 'New Driver', phoneNumber: '5551234567' });

    expect(await screen.findByText('Map page stub')).toBeInTheDocument();
    expect(patchBody).toEqual({ displayName: 'New Driver', phoneNumber: '5551234567' });
    // Pending data is cleared once the flow completes.
    expect(getPendingProfile()).toBeNull();
    expect(useAuthStore.getState().provisioning).toBe(false);
  });

  it('does not block account creation when the profile update fails', async () => {
    server.use(
      http.post(`${API_BASE}/auth/register`, () => HttpResponse.json(authResponse)),
      http.get(`${API_BASE}/auth/me`, () => HttpResponse.json(newcomer)),
      http.patch(`${API_BASE}/users/me`, () =>
        HttpResponse.json(apiErrorBody('INTERNAL', 'boom', 'trace-patch'), { status: 500 }),
      ),
    );

    await fillAndSubmit({ displayName: 'New Driver' });

    // Soft warning instead of a hard failure; the session stays authenticated.
    expect(await screen.findByText('Your account is ready')).toBeInTheDocument();
    expect(useAuthStore.getState().isAuthenticated).toBe(true);
    // Pending data is cleared even on the failure path.
    expect(getPendingProfile()).toBeNull();
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

    await fillAndSubmit({ email: 'taken@parkio.dev' });

    expect(await screen.findByText('Email already registered')).toBeInTheDocument();
    expect(screen.getByText('Trace: trace-reg-1')).toBeInTheDocument();
    expect(useAuthStore.getState().isAuthenticated).toBe(false);
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

    await fillAndSubmit();

    expect(await screen.findByText('Preparing your account')).toBeInTheDocument();
    expect(screen.queryByText('Map page stub')).not.toBeInTheDocument();
    const state = useAuthStore.getState();
    expect(state.isAuthenticated).toBe(true);
    expect(state.suspended).toBe(false);
    expect(state.provisioning).toBe(true);
  });
});
