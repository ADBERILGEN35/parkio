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
    useAuthStore.getState().setSession('access', 'refresh', user);

    useAuthStore.getState().markSuspended();

    expect(useAuthStore.getState().suspended).toBe(true);
  });

  it('markSuspended is a no-op during the provisioning grace window', () => {
    useAuthStore.getState().setSession('access', 'refresh', user);
    useAuthStore.getState().beginProvisioning();

    useAuthStore.getState().markSuspended();

    const state = useAuthStore.getState();
    expect(state.provisioning).toBe(true);
    expect(state.suspended).toBe(false);
  });

  it('marks suspended again once provisioning ends (no global masking)', () => {
    useAuthStore.getState().setSession('access', 'refresh', user);
    useAuthStore.getState().beginProvisioning();
    useAuthStore.getState().endProvisioning();

    useAuthStore.getState().markSuspended();

    expect(useAuthStore.getState().suspended).toBe(true);
  });

  it('setSession clears any prior provisioning/suspended flags', () => {
    useAuthStore.getState().setSession('access', 'refresh', user);
    useAuthStore.getState().beginProvisioning();

    useAuthStore.getState().setSession('access-2', 'refresh-2', user);

    const state = useAuthStore.getState();
    expect(state.provisioning).toBe(false);
    expect(state.suspended).toBe(false);
  });
});
