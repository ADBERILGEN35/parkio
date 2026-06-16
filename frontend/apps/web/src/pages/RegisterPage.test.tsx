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
import { CheckEmailPage } from './CheckEmailPage';
import { RegisterPage } from './RegisterPage';

const newcomer = {
  id: '6f9619ff-8b86-4d01-b42d-00cf4fc964ff',
  email: 'newcomer@parkio.dev',
  status: 'PENDING_VERIFICATION',
  roles: ['USER'],
};

const authResponse = {
  accessToken: null,
  tokenType: 'Bearer',
  accessTokenExpiresAt: null,
  refreshTokenExpiresAt: null,
  user: newcomer,
};

function renderRegister() {
  return renderWithProviders(
    <Routes>
      <Route path="/register" element={<RegisterPage />} />
      <Route path="/check-email" element={<CheckEmailPage />} />
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
    password: 'SaferPass123',
    confirmPassword: 'SaferPass123',
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

    await fillAndSubmit({ confirmPassword: 'Different123' });

    expect(await screen.findByText('Passwords do not match')).toBeInTheDocument();
    expect(registerCalls).toBe(0);
  });

  it('shows live password requirements', async () => {
    renderRegister();
    const user = userEvent.setup();

    expect(screen.getByText('Needed: At least 12 characters')).toBeInTheDocument();
    expect(screen.getByText('Needed: One lowercase letter')).toBeInTheDocument();
    expect(screen.getByText('Needed: One uppercase letter')).toBeInTheDocument();
    expect(screen.getByText('Needed: One number')).toBeInTheDocument();
    expect(screen.getByText('Needed: Not a common password')).toBeInTheDocument();

    await user.type(screen.getByLabelText('Password'), 'SaferPass123');

    expect(screen.getByText('Met: At least 12 characters')).toBeInTheDocument();
    expect(screen.getByText('Met: One lowercase letter')).toBeInTheDocument();
    expect(screen.getByText('Met: One uppercase letter')).toBeInTheDocument();
    expect(screen.getByText('Met: One number')).toBeInTheDocument();
    expect(screen.getByText('Met: Not a common password')).toBeInTheDocument();
  });

  it('blocks weak passwords before calling register', async () => {
    let registerCalls = 0;
    server.use(
      http.post(`${API_BASE}/auth/register`, () => {
        registerCalls += 1;
        return HttpResponse.json(authResponse);
      }),
    );

    await fillAndSubmit({ password: 'password123', confirmPassword: 'password123' });

    expect(await screen.findByText(/Password must be at least 12 characters/)).toBeInTheDocument();
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
    );

    await fillAndSubmit({ displayName: 'New Driver', phoneNumber: '5551234567' });

    await waitFor(() => expect(body).not.toBeNull());
    expect(Object.keys(body!).sort()).toEqual(['email', 'password']);
    expect(body!.email).toBe('newcomer@parkio.dev');
    expect(await screen.findByText('Check your email')).toBeInTheDocument();
    expect(useAuthStore.getState().isAuthenticated).toBe(false);
  });

  it('saves the captured display name and phone for after email verification login', async () => {
    server.use(
      http.post(`${API_BASE}/auth/register`, () => HttpResponse.json(authResponse)),
    );

    await fillAndSubmit({ displayName: 'New Driver', phoneNumber: '5551234567' });

    expect(await screen.findByText('Check your email')).toBeInTheDocument();
    expect(getPendingProfile()).toEqual({ displayName: 'New Driver', phoneNumber: '5551234567' });
    expect(useAuthStore.getState().provisioning).toBe(false);
  });

  it('does not authenticate immediately after account creation', async () => {
    server.use(
      http.post(`${API_BASE}/auth/register`, () => HttpResponse.json(authResponse)),
    );

    await fillAndSubmit({ displayName: 'New Driver' });

    expect(await screen.findByText('Verify your address before signing in.')).toBeInTheDocument();
    expect(useAuthStore.getState().isAuthenticated).toBe(false);
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

  it('resends verification from the check-email screen', async () => {
    let resendBody: Record<string, unknown> | null = null;
    server.use(
      http.post(`${API_BASE}/auth/register`, () => HttpResponse.json(authResponse)),
      http.post(`${API_BASE}/auth/resend-verification`, async ({ request }) => {
        resendBody = (await request.json()) as Record<string, unknown>;
        return new HttpResponse(null, { status: 202 });
      }),
    );

    await fillAndSubmit();

    expect(await screen.findByText('Check your email')).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole('button', { name: 'Resend verification' }));

    await waitFor(() => expect(resendBody).toEqual({ email: 'newcomer@parkio.dev' }));
    expect(screen.getByText('Verification email sent. Please check your inbox.')).toBeInTheDocument();
  });
});
