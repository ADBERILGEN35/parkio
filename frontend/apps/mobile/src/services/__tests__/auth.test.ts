import * as SecureStore from 'expo-secure-store';
import { refreshSession } from '@parkio/api-client';
import type { AuthResponse, User } from '@parkio/types';
import { useAuthStore } from '@/state/authStore';
import { authApi } from '../api';
import { bootstrapSession, signIn, signOut, signOutAll, signUp } from '../auth';
import { tokenStorage } from '../tokenStorage';

jest.mock('@parkio/api-client', () => ({
  refreshSession: jest.fn(),
}));

jest.mock('../api', () => ({
  authApi: {
    login: jest.fn(),
    register: jest.fn(),
    logout: jest.fn(),
    logoutAll: jest.fn(),
    forgotPassword: jest.fn(),
  },
}));

const user: User = { id: 'user-1', email: 'driver@parkio.dev', status: 'ACTIVE', roles: ['USER'] };

const authResponse: AuthResponse = {
  accessToken: 'access-token',
  tokenType: 'Bearer',
  accessTokenExpiresAt: '2026-07-01T12:00:00Z',
  refreshTokenExpiresAt: '2026-07-29T12:00:00Z',
  refreshToken: 'refresh-token',
  user,
};

const flush = () => new Promise((resolve) => setTimeout(resolve, 0));

function resetAuthState() {
  useAuthStore.setState({
    user: null,
    roles: [],
    status: null,
    isAuthenticated: false,
    suspended: false,
    bootstrapPending: true,
    sessionEpoch: 0,
  });
}

describe('mobile auth service', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    (SecureStore as unknown as { __resetStore: () => void }).__resetStore();
    tokenStorage.clearTokens();
    resetAuthState();
  });

  it('stores access and refresh tokens on login', async () => {
    jest.mocked(authApi.login).mockResolvedValue(authResponse);

    await signIn({ email: user.email, password: 'StrongerPass123' });
    await flush();

    expect(tokenStorage.getAccessToken()).toBe('access-token');
    expect(tokenStorage.getRefreshToken()).toBe('refresh-token');
    await expect(SecureStore.getItemAsync('parkio.accessToken')).resolves.toBe('access-token');
    await expect(SecureStore.getItemAsync('parkio.refreshToken')).resolves.toBe('refresh-token');
    await expect(SecureStore.getItemAsync('parkio.userId')).resolves.toBe('user-1');
    expect(useAuthStore.getState().isAuthenticated).toBe(true);
  });

  it('rejects a mobile login response that does not include a refresh token', async () => {
    jest.mocked(authApi.login).mockResolvedValue({ ...authResponse, refreshToken: null });

    await expect(signIn({ email: user.email, password: 'StrongerPass123' })).rejects.toThrow(
      'refresh token',
    );
    expect(tokenStorage.getAccessToken()).toBeNull();
    expect(useAuthStore.getState().isAuthenticated).toBe(false);
  });

  it('sends the stored refresh token on current-session logout and clears local state', async () => {
    tokenStorage.setTokens({ accessToken: 'access-token', refreshToken: 'refresh-token' });
    useAuthStore.getState().setSession(user);

    await signOut();
    await flush();

    expect(authApi.logout).toHaveBeenCalledWith('refresh-token');
    expect(tokenStorage.getAccessToken()).toBeNull();
    expect(tokenStorage.getRefreshToken()).toBeNull();
    expect(useAuthStore.getState().isAuthenticated).toBe(false);
  });

  it('clears local tokens after logout-all', async () => {
    tokenStorage.setTokens({ accessToken: 'access-token', refreshToken: 'refresh-token' });
    useAuthStore.getState().setSession(user);

    await signOutAll();
    await flush();

    expect(authApi.logoutAll).toHaveBeenCalledTimes(1);
    expect(tokenStorage.getRefreshToken()).toBeNull();
    expect(useAuthStore.getState().isAuthenticated).toBe(false);
  });

  it('clears stale hydrated tokens when bootstrap refresh cannot restore a session', async () => {
    await SecureStore.setItemAsync('parkio.accessToken', 'stale-access');
    await SecureStore.setItemAsync('parkio.refreshToken', 'stale-refresh');
    jest.mocked(refreshSession).mockResolvedValue(null);

    await bootstrapSession();
    await flush();

    expect(refreshSession).toHaveBeenCalledTimes(1);
    expect(tokenStorage.getAccessToken()).toBeNull();
    expect(tokenStorage.getRefreshToken()).toBeNull();
    expect(useAuthStore.getState().isAuthenticated).toBe(false);
    expect(useAuthStore.getState().bootstrapPending).toBe(false);
  });

  it('does not authenticate immediately after register because backend requires verification', async () => {
    jest.mocked(authApi.register).mockResolvedValue({ ...authResponse, accessToken: null, refreshToken: null });

    await signUp({ email: user.email, password: 'StrongerPass123' });
    await flush();

    expect(tokenStorage.getAccessToken()).toBeNull();
    expect(tokenStorage.getRefreshToken()).toBeNull();
    expect(useAuthStore.getState().isAuthenticated).toBe(false);
  });
});
