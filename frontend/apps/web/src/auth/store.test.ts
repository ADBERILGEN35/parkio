import type { User } from '@parkio/types';
import { beforeEach, describe, expect, it } from 'vitest';
import { useAuthStore } from './store';

const user: User = {
  id: '6f9619ff-8b86-4d01-b42d-00cf4fc964ff',
  email: 'tester@parkio.dev',
  status: 'ACTIVE',
  roles: ['USER'],
};

describe('auth store — provisioning grace vs. suspension', () => {
  beforeEach(() => useAuthStore.getState().clearSession());

  it('markSuspended marks suspended for a real (non-provisioning) session', () => {
    useAuthStore.getState().setSession('access', user);

    useAuthStore.getState().markSuspended();

    expect(useAuthStore.getState().suspended).toBe(true);
  });

  it('markSuspended is a no-op during the provisioning grace window', () => {
    useAuthStore.getState().setSession('access', user);
    useAuthStore.getState().beginProvisioning();

    useAuthStore.getState().markSuspended();

    const state = useAuthStore.getState();
    expect(state.provisioning).toBe(true);
    expect(state.suspended).toBe(false);
  });

  it('marks suspended again once provisioning ends (no global masking)', () => {
    useAuthStore.getState().setSession('access', user);
    useAuthStore.getState().beginProvisioning();
    useAuthStore.getState().endProvisioning();

    useAuthStore.getState().markSuspended();

    expect(useAuthStore.getState().suspended).toBe(true);
  });

  it('setSession clears any prior provisioning/suspended flags', () => {
    useAuthStore.getState().setSession('access', user);
    useAuthStore.getState().beginProvisioning();

    useAuthStore.getState().setSession('access-2', user);

    const state = useAuthStore.getState();
    expect(state.provisioning).toBe(false);
    expect(state.suspended).toBe(false);
  });

  it('does not persist tokens to localStorage', () => {
    useAuthStore.getState().setSession('access', user);

    expect(localStorage.getItem('parkio.accessToken')).toBeNull();
    expect(localStorage.getItem('parkio.refreshToken')).toBeNull();
    expect(useAuthStore.getState().accessToken).toBe('access');
  });

  it('cleans up legacy localStorage tokens from older builds on clear', () => {
    localStorage.setItem('parkio.accessToken', 'legacy-access');
    localStorage.setItem('parkio.refreshToken', 'legacy-refresh');

    useAuthStore.getState().clearSession();

    expect(localStorage.getItem('parkio.accessToken')).toBeNull();
    expect(localStorage.getItem('parkio.refreshToken')).toBeNull();
  });
});

describe('auth store — bootstrap + session epoch', () => {
  beforeEach(() => useAuthStore.getState().clearSession());

  it('setSession and clearSession both end the bootstrap-pending state', () => {
    useAuthStore.setState({ bootstrapPending: true });
    useAuthStore.getState().setSession('access', user);
    expect(useAuthStore.getState().bootstrapPending).toBe(false);

    useAuthStore.setState({ bootstrapPending: true });
    useAuthStore.getState().clearSession();
    expect(useAuthStore.getState().bootstrapPending).toBe(false);
  });

  it('endBootstrap clears the bootstrap-pending flag without touching the session', () => {
    useAuthStore.getState().setSession('access', user);
    useAuthStore.setState({ bootstrapPending: true });

    useAuthStore.getState().endBootstrap();

    expect(useAuthStore.getState().bootstrapPending).toBe(false);
    expect(useAuthStore.getState().isAuthenticated).toBe(true);
  });

  it('advances the session epoch on every clear (so in-flight refresh can detect logout)', () => {
    const before = useAuthStore.getState().sessionEpoch;

    useAuthStore.getState().clearSession();

    expect(useAuthStore.getState().sessionEpoch).toBe(before + 1);
  });
});
