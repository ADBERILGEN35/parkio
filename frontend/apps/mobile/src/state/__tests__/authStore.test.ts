import type { User } from '@parkio/types';
import { useAuthStore } from '../authStore';

const user: User = { id: 'u1', email: 'driver@parkio.dev', status: 'ACTIVE', roles: ['USER'] };

describe('authStore', () => {
  beforeEach(() => {
    useAuthStore.setState({
      user: null,
      roles: [],
      status: null,
      isAuthenticated: false,
      suspended: false,
      bootstrapPending: true,
      sessionEpoch: 0,
    });
  });

  it('marks the user authenticated on setSession', () => {
    useAuthStore.getState().setSession(user);
    const state = useAuthStore.getState();
    expect(state.isAuthenticated).toBe(true);
    expect(state.user?.email).toBe('driver@parkio.dev');
    expect(state.roles).toEqual(['USER']);
  });

  it('clears the session and bumps sessionEpoch (guards late refresh)', () => {
    useAuthStore.getState().setSession(user);
    const before = useAuthStore.getState().sessionEpoch;
    useAuthStore.getState().clearSession();
    const state = useAuthStore.getState();
    expect(state.isAuthenticated).toBe(false);
    expect(state.user).toBeNull();
    expect(state.sessionEpoch).toBe(before + 1);
  });

  it('ends bootstrap', () => {
    expect(useAuthStore.getState().bootstrapPending).toBe(true);
    useAuthStore.getState().endBootstrap();
    expect(useAuthStore.getState().bootstrapPending).toBe(false);
  });

  it('flags a suspended account', () => {
    useAuthStore.getState().markSuspended();
    expect(useAuthStore.getState().suspended).toBe(true);
  });
});
