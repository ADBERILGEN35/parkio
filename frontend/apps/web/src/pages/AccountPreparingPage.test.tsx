import { http, HttpResponse } from 'msw';
import { act, fireEvent, screen } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '@/auth/store';
import { API_BASE, apiErrorBody, server } from '@/test/server';
import { renderWithProviders, resetAuth, signInAs } from '@/test/utils';
import { AccountPreparingPage } from './AccountPreparingPage';

const meUser = {
  id: '6f9619ff-8b86-4d01-b42d-00cf4fc964ff',
  email: 'tester@parkio.dev',
  status: 'ACTIVE',
  roles: ['USER'],
};

/** Mirrors the post-register handoff: an authenticated session inside the grace window. */
function renderPreparing() {
  signInAs(['USER']);
  useAuthStore.getState().beginProvisioning();
  return renderWithProviders(
    <Routes>
      <Route path="/preparing" element={<AccountPreparingPage />} />
      <Route path="/map" element={<div>Map page stub</div>} />
    </Routes>,
    { initialEntries: ['/preparing'] },
  );
}

function notActive() {
  return HttpResponse.json(
    apiErrorBody('ACCOUNT_NOT_ACTIVE', 'Account is not active', 'trace-prov-1'),
    { status: 403 },
  );
}

describe('AccountPreparingPage', () => {
  beforeEach(() => resetAuth());
  afterEach(() => vi.useRealTimers());

  it('forwards to /map once the profile is ready', async () => {
    server.use(http.get(`${API_BASE}/auth/me`, () => HttpResponse.json(meUser)));

    renderPreparing();

    expect(await screen.findByText('Map page stub')).toBeInTheDocument();
    const state = useAuthStore.getState();
    expect(state.suspended).toBe(false);
    expect(state.provisioning).toBe(false);
  });

  it('shows the preparing state and never marks suspended while provisioning', async () => {
    server.use(http.get(`${API_BASE}/auth/me`, () => notActive()));

    renderPreparing();

    expect(await screen.findByText('Preparing your account')).toBeInTheDocument();
    expect(screen.getByText('This usually takes a few seconds.')).toBeInTheDocument();
    expect(screen.queryByText('Map page stub')).not.toBeInTheDocument();
    // 403 ACCOUNT_NOT_ACTIVE during the grace window must NOT flip the global flag.
    expect(useAuthStore.getState().suspended).toBe(false);
  });

  it('retries on ACCOUNT_NOT_ACTIVE and forwards once provisioning completes', async () => {
    vi.useFakeTimers();
    let calls = 0;
    server.use(
      http.get(`${API_BASE}/auth/me`, () => {
        calls += 1;
        return calls === 1 ? notActive() : HttpResponse.json(meUser);
      }),
    );

    renderPreparing();
    // First attempt (immediate) 403 → schedule retry → second attempt 200 → /map.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1_100);
    });

    expect(screen.getByText('Map page stub')).toBeInTheDocument();
    expect(calls).toBeGreaterThanOrEqual(2);
    expect(useAuthStore.getState().suspended).toBe(false);
  });

  it('shows retry + sign out after the readiness window times out', async () => {
    vi.useFakeTimers();
    server.use(http.get(`${API_BASE}/auth/me`, () => notActive()));

    renderPreparing();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(13_500);
    });

    expect(
      screen.getByText('This is taking longer than expected.', { exact: false }),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /try again/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /sign out/i })).toBeInTheDocument();
    expect(useAuthStore.getState().suspended).toBe(false);
  });

  it('retries from the timed-out state and forwards when ready', async () => {
    vi.useFakeTimers();
    let ready = false;
    server.use(
      http.get(`${API_BASE}/auth/me`, () => (ready ? HttpResponse.json(meUser) : notActive())),
    );

    renderPreparing();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(13_500);
    });
    expect(screen.getByRole('button', { name: /try again/i })).toBeInTheDocument();

    ready = true;
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /try again/i }));
      await vi.advanceTimersByTimeAsync(100);
    });

    expect(screen.getByText('Map page stub')).toBeInTheDocument();
  });
});
