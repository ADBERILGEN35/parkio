import type { User } from '@parkio/types';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createAuthStore, type AuthStore } from './auth-store';

let authStore: AuthStore;

const user: User = {
  id: '6f9619ff-8b86-4d01-b42d-00cf4fc964ff',
  email: 'tester@parkio.dev',
  status: 'ACTIVE',
  roles: ['USER'],
};

const replacementUser: User = {
  id: '0f4af9c8-92db-49b1-bb3e-e1e09f727c91',
  email: 'replacement@parkio.dev',
  status: 'ACTIVE',
  roles: ['ADMIN'],
};

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  authStore = createAuthStore();
});

describe('application-scoped authentication state', () => {
  it('starts bootstrapping with an anonymous derived identity', () => {
    const state = authStore.getState();

    expect(state.lifecycle).toBe('bootstrapping');
    expect(state.bootstrapPending).toBe(true);
    expect(state.identity).toEqual({
      state: 'anonymous',
      userId: null,
      roles: [],
      accountStatus: null,
      restriction: null,
    });
  });

  it('derives authenticated identity, roles, and account status from a session', () => {
    authStore.getState().setSession('access', user);

    const state = authStore.getState();
    expect(state.lifecycle).toBe('authenticated');
    expect(state.isAuthenticated).toBe(true);
    expect(state.identity).toEqual({
      state: 'authenticated',
      userId: user.id,
      roles: ['USER'],
      accountStatus: 'ACTIVE',
      restriction: null,
    });
  });

  it('represents provisioning as an explicit session state', () => {
    authStore.getState().setSession('access', user);
    authStore.getState().beginProvisioning();

    expect(authStore.getState().lifecycle).toBe('provisioning');
    expect(authStore.getState().identity.state).toBe('provisioning');
    expect(authStore.getState().isAuthenticated).toBe(true);
  });

  it('keeps ACCOUNT_NOT_ACTIVE distinct and suppresses it only during provisioning', () => {
    authStore.getState().setSession('access', user);
    authStore.getState().beginProvisioning();
    authStore.getState().markAccountRestricted('ACCOUNT_NOT_ACTIVE');
    expect(authStore.getState().lifecycle).toBe('provisioning');

    authStore.getState().endProvisioning();
    authStore.getState().markAccountRestricted('ACCOUNT_NOT_ACTIVE');

    const state = authStore.getState();
    expect(state.lifecycle).toBe('account-restricted');
    expect(state.restriction).toBe('ACCOUNT_NOT_ACTIVE');
    expect(state.suspended).toBe(true);
    expect(state.isAuthenticated).toBe(false);
  });

  it('keeps ACCOUNT_NOT_VERIFIED distinct from ACCOUNT_NOT_ACTIVE', () => {
    authStore.getState().setSession('access', user);
    authStore.getState().markAccountRestricted('ACCOUNT_NOT_VERIFIED');

    const state = authStore.getState();
    expect(state.lifecycle).toBe('account-restricted');
    expect(state.restriction).toBe('ACCOUNT_NOT_VERIFIED');
    expect(state.suspended).toBe(false);
  });

  it('derives ACCOUNT_NOT_VERIFIED from a pending-verification identity', () => {
    authStore.getState().setSession('access', {
      ...user,
      status: 'PENDING_VERIFICATION',
    });

    expect(authStore.getState().lifecycle).toBe('account-restricted');
    expect(authStore.getState().restriction).toBe('ACCOUNT_NOT_VERIFIED');
    expect(authStore.getState().suspended).toBe(false);
  });

  it('settles an empty bootstrap as anonymous', () => {
    authStore.getState().endBootstrap();

    expect(authStore.getState().lifecycle).toBe('anonymous');
    expect(authStore.getState().bootstrapPending).toBe(false);
  });

  it('creates isolated state for each runtime-owned store', () => {
    const otherStore = createAuthStore();

    authStore.getState().setSession('first-token', user);

    expect(authStore.getState().accessToken).toBe('first-token');
    expect(otherStore.getState().accessToken).toBeNull();
    expect(otherStore.getState().lifecycle).toBe('bootstrapping');
    expect(authStore.getState().sessionEpoch).toBe(1);
    expect(otherStore.getState().sessionEpoch).toBe(0);
  });
});

describe('session generation and identity notification', () => {
  it('advances generation for every explicit full-session replacement', () => {
    const initialEpoch = authStore.getState().sessionEpoch;

    authStore.getState().setSession('first-token', user);
    const firstSessionEpoch = authStore.getState().sessionEpoch;
    authStore.getState().setSession('second-token', user);

    expect(firstSessionEpoch).toBe(initialEpoch + 1);
    expect(authStore.getState().sessionEpoch).toBe(firstSessionEpoch + 1);
  });

  it('advances generation for deterministic identity-state transitions', () => {
    authStore.getState().setSession('access', user);
    const authenticatedEpoch = authStore.getState().sessionEpoch;

    authStore.getState().beginProvisioning();
    const provisioningEpoch = authStore.getState().sessionEpoch;
    authStore.getState().endProvisioning();
    const settledEpoch = authStore.getState().sessionEpoch;
    authStore.getState().markAccountRestricted('ACCOUNT_NOT_ACTIVE');
    const restrictedEpoch = authStore.getState().sessionEpoch;
    authStore.getState().setSession('replacement', replacementUser);

    expect(provisioningEpoch).toBe(authenticatedEpoch + 1);
    expect(settledEpoch).toBe(provisioningEpoch + 1);
    expect(restrictedEpoch).toBe(settledEpoch + 1);
    expect(authStore.getState().sessionEpoch).toBe(restrictedEpoch + 1);
    expect(authStore.getState().identity.userId).toBe(replacementUser.id);
  });

  it('treats replacement from provisioning as a new session generation', () => {
    authStore.getState().setSession('access', user);
    authStore.getState().beginProvisioning();
    const provisioningEpoch = authStore.getState().sessionEpoch;

    authStore.getState().setSession('replacement', replacementUser);

    expect(authStore.getState().sessionEpoch).toBe(provisioningEpoch + 1);
    expect(authStore.getState().lifecycle).toBe('authenticated');
    expect(authStore.getState().identity.userId).toBe(replacementUser.id);
  });

  it('does not advance generation for a harmless update to the same active identity', () => {
    authStore.getState().setSession('access', {
      ...user,
      roles: ['USER', 'MODERATOR'],
    });
    const sessionEpoch = authStore.getState().sessionEpoch;

    authStore.getState().setUser({
      ...user,
      email: 'updated-email@parkio.dev',
      roles: ['MODERATOR', 'USER'],
    });

    expect(authStore.getState().sessionEpoch).toBe(sessionEpoch);
  });

  it('keeps a same-identity SDK restoration in the active generation', () => {
    authStore.getState().setSession('access', user);
    const sessionEpoch = authStore.getState().sessionEpoch;

    const restored = authStore
      .getState()
      .restoreSession(sessionEpoch, 'refreshed-access', user);

    expect(restored).toBe(true);
    expect(authStore.getState().sessionEpoch).toBe(sessionEpoch);
    expect(authStore.getState().accessToken).toBe('refreshed-access');
  });

  it('clears once and leaves duplicate teardown idempotent', () => {
    authStore.getState().setSession('access', user);
    const before = authStore.getState().sessionEpoch;

    expect(authStore.getState().clearSession()).toBe(true);
    expect(authStore.getState().clearSession()).toBe(false);

    expect(authStore.getState().sessionEpoch).toBe(before + 1);
    expect(authStore.getState().lifecycle).toBe('anonymous');
  });

  it('rejects a late restore from an invalidated generation', () => {
    const epochAtRefreshStart = authStore.getState().sessionEpoch;
    authStore.getState().clearSession();

    const restored = authStore
      .getState()
      .restoreSession(epochAtRefreshStart, 'late-token', user);

    expect(restored).toBe(false);
    expect(authStore.getState().accessToken).toBeNull();
    expect(authStore.getState().lifecycle).toBe('anonymous');
  });

  it('rejects a late restore after another identity is established', () => {
    authStore.getState().setSession('identity-a-token', user);
    const epochAtRefreshStart = authStore.getState().sessionEpoch;
    authStore.getState().setSession('identity-b-token', replacementUser);

    const restored = authStore
      .getState()
      .restoreSession(epochAtRefreshStart, 'late-a-token', user);

    expect(restored).toBe(false);
    expect(authStore.getState().accessToken).toBe('identity-b-token');
    expect(authStore.getState().identity.userId).toBe(replacementUser.id);
  });

  it('notifies identity transitions without exposing credentials', () => {
    const listener = vi.fn();
    authStore.subscribeIdentityChanges(listener);

    authStore.getState().setSession('secret-access-token', user);
    authStore.getState().clearSession();

    expect(listener).toHaveBeenCalledTimes(2);
    expect(listener.mock.calls[0]?.[0].current.state).toBe('authenticated');
    expect(JSON.stringify(listener.mock.calls)).not.toContain('secret-access-token');
  });

  it('does not notify when an idempotent teardown leaves identity unchanged', () => {
    authStore.getState().endBootstrap();
    const listener = vi.fn();
    authStore.subscribeIdentityChanges(listener);

    authStore.getState().clearSession();

    expect(listener).not.toHaveBeenCalled();
  });
});

describe('credential non-persistence', () => {
  it('keeps access tokens only in memory', () => {
    const localSet = vi.spyOn(Storage.prototype, 'setItem');

    authStore.getState().setSession('memory-only-token', user);

    expect(authStore.getState().accessToken).toBe('memory-only-token');
    expect(localSet).not.toHaveBeenCalled();
    expect(localStorage.getItem('parkio.accessToken')).toBeNull();
    expect(sessionStorage.getItem('parkio.accessToken')).toBeNull();
  });

  it('removes legacy browser token keys during teardown without persisting replacements', () => {
    localStorage.setItem('parkio.accessToken', 'legacy-access');
    localStorage.setItem('parkio.refreshToken', 'legacy-refresh');
    authStore.getState().setSession('memory-only-token', user);

    authStore.getState().clearSession();

    expect(localStorage.getItem('parkio.accessToken')).toBeNull();
    expect(localStorage.getItem('parkio.refreshToken')).toBeNull();
  });
});
