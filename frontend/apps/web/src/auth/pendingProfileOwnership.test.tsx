import type { User } from '@parkio/types';
import { http, HttpResponse } from 'msw';
import { StrictMode } from 'react';
import { act, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import {
  clearPendingProfile,
  getPendingProfile,
  resetPendingProfileMemoryForTests,
  setPendingProfile,
} from '@/auth/pendingProfile';
import { API_BASE, server } from '@/test/server';
import { createTestAppRuntime, createTestQueryClient, renderWithProviders } from '@/test/utils';
import { AccountPreparingPage } from '@/pages/AccountPreparingPage';
import { CheckEmailPage } from '@/pages/CheckEmailPage';
import { LoginPage } from '@/pages/LoginPage';
import { RegisterPage } from '@/pages/RegisterPage';

/**
 * CX-F04 / U04: registration-captured profile fields (display name, in-memory
 * phone) must only ever be applied to the account that registered them.
 * Synthetic identities only.
 */
const ACCOUNT_A: User = {
  id: '1a1a1a1a-0000-4000-8000-00000000000a',
  email: 'synthetic-a@parkio.test',
  status: 'ACTIVE',
  roles: ['USER'],
};

const ACCOUNT_B: User = {
  id: '2b2b2b2b-0000-4000-8000-00000000000b',
  email: 'synthetic-b@parkio.test',
  status: 'ACTIVE',
  roles: ['USER'],
};

function authResponse(user: User, accessToken: string | null = `token-${user.id}`) {
  return {
    accessToken,
    tokenType: 'Bearer',
    accessTokenExpiresAt: accessToken ? '2026-10-01T11:00:00Z' : null,
    refreshTokenExpiresAt: accessToken ? '2026-11-01T10:00:00Z' : null,
    user: accessToken ? user : { ...user, status: 'PENDING_VERIFICATION' },
  };
}

interface PatchRecord {
  body: Record<string, unknown>;
  authorization: string | null;
}

/** Records every profile mutation with the bearer it was sent under. */
function recordProfilePatches(): PatchRecord[] {
  const patches: PatchRecord[] = [];
  server.use(
    http.patch(`${API_BASE}/users/me`, async ({ request }) => {
      patches.push({
        body: (await request.json()) as Record<string, unknown>,
        authorization: request.headers.get('authorization'),
      });
      return HttpResponse.json({});
    }),
  );
  return patches;
}

/** /auth/me answers for whichever bearer the request actually carried. */
function meByBearer() {
  server.use(
    http.get(`${API_BASE}/auth/me`, ({ request }) => {
      const auth = request.headers.get('authorization') ?? '';
      if (auth.endsWith(ACCOUNT_A.id)) return HttpResponse.json(ACCOUNT_A);
      if (auth.endsWith(ACCOUNT_B.id)) return HttpResponse.json(ACCOUNT_B);
      return HttpResponse.json({}, { status: 401 });
    }),
  );
}

function renderAuthFlow(initialEntry: string, sessionUser?: User) {
  const runtime = createTestAppRuntime(createTestQueryClient(), [initialEntry]);
  if (sessionUser) {
    runtime.authStore.getState().setSession(`token-${sessionUser.id}`, sessionUser);
    runtime.authStore.getState().beginProvisioning();
  }
  return renderWithProviders(
    <Routes>
      <Route path="/register" element={<RegisterPage />} />
      <Route path="/check-email" element={<CheckEmailPage />} />
      <Route path="/login" element={<LoginPage />} />
      <Route path="/preparing" element={<AccountPreparingPage />} />
      <Route path="/map" element={<div>Map page stub</div>} />
    </Routes>,
    { initialEntries: [initialEntry], runtime },
  );
}

async function registerThroughForm(account: User, phoneNumber = '5551234567') {
  server.use(
    http.post(`${API_BASE}/auth/register`, () =>
      HttpResponse.json(authResponse(account, null), { status: 201 }),
    ),
  );
  const rendered = renderAuthFlow('/register');
  const user = userEvent.setup();
  await user.type(screen.getByLabelText('Full name'), 'Registrant A');
  await user.type(screen.getByLabelText('Email'), account.email);
  await user.type(screen.getByLabelText(/Phone number/), phoneNumber);
  await user.type(screen.getByLabelText('Password'), 'SaferPass123');
  await user.type(screen.getByLabelText('Confirm password'), 'SaferPass123');
  await user.click(screen.getByRole('checkbox', { name: /I agree/ }));
  await user.click(screen.getByRole('button', { name: 'Create account' }));
  expect(await screen.findByText('Check your email')).toBeInTheDocument();
  return rendered;
}

async function loginThroughForm(account: User) {
  server.use(
    http.post(`${API_BASE}/auth/login`, () => HttpResponse.json(authResponse(account))),
  );
  const user = userEvent.setup();
  await user.type(screen.getByLabelText('Email'), account.email);
  await user.type(screen.getByLabelText('Password'), 'SaferPass123');
  await user.click(screen.getByRole('button', { name: 'Sign in' }));
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((r) => {
    resolve = r;
  });
  return { promise, resolve };
}

describe('pending registration profile ownership (CX-F04)', () => {
  beforeEach(() => clearPendingProfile());
  afterEach(() => clearPendingProfile());

  it('A registration → B login in the same tab sends zero profile PATCH requests', async () => {
    const patches = recordProfilePatches();
    meByBearer();
    const { runtime } = await registerThroughForm(ACCOUNT_A);

    await act(() => runtime.router.navigate('/login'));
    await loginThroughForm(ACCOUNT_B);

    await waitFor(() => expect(runtime.authStore.getState().lifecycle).toBe('authenticated'));
    expect(runtime.authStore.getState().identity.userId).toBe(ACCOUNT_B.id);
    // Give any stray preparing work a chance to run before asserting absence.
    await act(async () => {
      await new Promise((r) => setTimeout(r, 50));
    });
    expect(patches).toEqual([]);
    expect(getPendingProfile()).toBeNull();
    expect(sessionStorage.getItem('parkio.pendingProfile')).toBeNull();
  });

  it('A registration → A login applies exactly the intended update once', async () => {
    const patches = recordProfilePatches();
    meByBearer();
    const { runtime } = await registerThroughForm(ACCOUNT_A);

    await act(() => runtime.router.navigate('/login'));
    await loginThroughForm(ACCOUNT_A);

    await waitFor(() => expect(runtime.authStore.getState().lifecycle).toBe('authenticated'));
    expect(patches).toEqual([
      {
        body: { displayName: 'Registrant A', phoneNumber: '5551234567' },
        authorization: `Bearer token-${ACCOUNT_A.id}`,
      },
    ]);
    expect(getPendingProfile()).toBeNull();
  });

  it('does not PATCH when the session switches to B while /auth/me for A is in flight', async () => {
    const patches = recordProfilePatches();
    const meGate = deferred<void>();
    server.use(
      http.get(`${API_BASE}/auth/me`, async () => {
        await meGate.promise;
        return HttpResponse.json(ACCOUNT_A);
      }),
    );
    setPendingProfile({ displayName: 'Registrant A', phoneNumber: '5551234567' }, ACCOUNT_A.id);
    const { runtime } = renderAuthFlow('/preparing', ACCOUNT_A);

    // Session changes to B without the normal logout cleanup path.
    act(() => {
      runtime.authStore.getState().setSession(`token-${ACCOUNT_B.id}`, ACCOUNT_B);
    });
    await act(async () => {
      meGate.resolve();
      await new Promise((r) => setTimeout(r, 50));
    });

    expect(patches).toEqual([]);
    // A's late /me answer must not overwrite B's session identity.
    expect(runtime.authStore.getState().user?.id).toBe(ACCOUNT_B.id);
  });

  it('does not PATCH when the preparing screen resolves B while pending data belongs to A', async () => {
    const patches = recordProfilePatches();
    meByBearer();
    setPendingProfile({ displayName: 'Registrant A', phoneNumber: '5551234567' }, ACCOUNT_A.id);
    const { runtime } = renderAuthFlow('/preparing', ACCOUNT_B);

    await waitFor(() => expect(runtime.authStore.getState().lifecycle).toBe('authenticated'));
    expect(patches).toEqual([]);
    expect(getPendingProfile()).toBeNull();
  });

  it('never applies a legacy pending payload that has no owner', async () => {
    const patches = recordProfilePatches();
    meByBearer();
    sessionStorage.setItem(
      'parkio.pendingProfile',
      JSON.stringify({ displayName: 'Legacy Name', needsPhoneReentry: true }),
    );
    const { runtime } = renderAuthFlow('/login');
    await loginThroughForm(ACCOUNT_B);

    await waitFor(() => expect(runtime.authStore.getState().lifecycle).toBe('authenticated'));
    await act(async () => {
      await new Promise((r) => setTimeout(r, 50));
    });
    expect(patches).toEqual([]);
    expect(sessionStorage.getItem('parkio.pendingProfile')).toBeNull();
    expect(screen.queryByText(/phone number was not kept/)).not.toBeInTheDocument();
  });

  it('after a reload (phone memory lost), a different account neither receives the name nor a phone prompt', async () => {
    const patches = recordProfilePatches();
    meByBearer();
    setPendingProfile({ displayName: 'Registrant A', phoneNumber: '5551234567' }, ACCOUNT_A.id);
    resetPendingProfileMemoryForTests();

    const { runtime } = renderAuthFlow('/login');
    await loginThroughForm(ACCOUNT_B);

    await waitFor(() => expect(runtime.authStore.getState().lifecycle).toBe('authenticated'));
    await act(async () => {
      await new Promise((r) => setTimeout(r, 50));
    });
    expect(patches).toEqual([]);
    expect(screen.queryByText(/phone number was not kept/)).not.toBeInTheDocument();
  });

  it('after a reload, the owning account gets only the display name and a phone re-entry prompt', async () => {
    const patches = recordProfilePatches();
    meByBearer();
    setPendingProfile({ displayName: 'Registrant A', phoneNumber: '5551234567' }, ACCOUNT_A.id);
    resetPendingProfileMemoryForTests();

    renderAuthFlow('/login');
    await loginThroughForm(ACCOUNT_A);

    expect(await screen.findByText(/phone number was not kept/)).toBeInTheDocument();
    expect(patches.map((p) => p.body)).toEqual([{ displayName: 'Registrant A' }]);
  });

  it('does not carry an earlier registration phone into a later registration', () => {
    setPendingProfile({ displayName: 'Registrant A', phoneNumber: '5551234567' }, ACCOUNT_A.id);
    setPendingProfile({ displayName: 'Registrant B' }, ACCOUNT_B.id);

    const pending = getPendingProfile();
    expect(pending?.ownerUserId).toBe(ACCOUNT_B.id);
    expect(pending?.phoneNumber).toBeUndefined();
    expect(pending?.needsPhoneReentry).toBeUndefined();
  });

  it('logout clears the pending state so a later login of another account applies nothing', async () => {
    const patches = recordProfilePatches();
    meByBearer();
    setPendingProfile({ displayName: 'Registrant A' }, ACCOUNT_A.id);
    const { runtime } = renderAuthFlow('/login');
    act(() => {
      runtime.authStore.getState().setSession(`token-${ACCOUNT_A.id}`, ACCOUNT_A);
      runtime.authStore.getState().clearSession();
    });
    expect(getPendingProfile()).toBeNull();

    await loginThroughForm(ACCOUNT_B);
    await waitFor(() => expect(runtime.authStore.getState().lifecycle).toBe('authenticated'));
    expect(patches).toEqual([]);
  });

  it('StrictMode double-mounted preparation issues a single profile PATCH', async () => {
    const patches = recordProfilePatches();
    meByBearer();
    setPendingProfile({ displayName: 'Registrant A' }, ACCOUNT_A.id);
    const runtime = createTestAppRuntime(createTestQueryClient(), ['/preparing']);
    runtime.authStore.getState().setSession(`token-${ACCOUNT_A.id}`, ACCOUNT_A);
    runtime.authStore.getState().beginProvisioning();
    renderWithProviders(
      <StrictMode>
        <AccountPreparingPage />
      </StrictMode>,
      { initialEntries: ['/preparing'], runtime },
    );

    await waitFor(() => expect(runtime.authStore.getState().lifecycle).toBe('authenticated'));
    await act(async () => {
      await new Promise((r) => setTimeout(r, 50));
    });
    expect(patches.map((p) => p.body)).toEqual([{ displayName: 'Registrant A' }]);
  });
});
