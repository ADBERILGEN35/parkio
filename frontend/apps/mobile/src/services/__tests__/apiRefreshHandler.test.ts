import * as SecureStore from 'expo-secure-store';
import {
  AccountNotActiveError,
  ParkioApiError,
  UnauthorizedError,
  setRefreshHandler,
} from '@parkio/api-client';
import type { AuthResponse, User } from '@parkio/types';
import { useAuthStore } from '@/state/authStore';
import { tokenStorage } from '../tokenStorage';

/**
 * Tests the real refresh handler wired by `@/services/api`, captured through a
 * mocked `setRefreshHandler`. The contract under test: a definitive 401 clears
 * the stored session (the refresh token is invalid/revoked), while transient
 * failures — offline, timeouts, backend 5xx — must keep the keystore intact so
 * the session survives until connectivity returns.
 */

const mockRefresh = jest.fn();

jest.mock('@parkio/api-client', () => {
  const actual = jest.requireActual('@parkio/api-client');
  return {
    ...actual,
    setRefreshHandler: jest.fn(),
    createApiClient: jest.fn(() => ({})),
    // `mockRefresh` is referenced lazily: this factory runs during the hoisted
    // `import '../api'`, before the test file's const initialisers.
    createAuthApi: jest.fn(() => ({ refresh: (token: string) => mockRefresh(token) })),
    createUsersApi: jest.fn(() => ({})),
    createParkingApi: jest.fn(() => ({})),
    createNotificationsApi: jest.fn(() => ({})),
    createGamificationApi: jest.fn(() => ({})),
    createGeocodingApi: jest.fn(() => ({})),
    createModerationApi: jest.fn(() => ({})),
    createAnalyticsApi: jest.fn(() => ({})),
  };
});

// Importing the module under test registers the handler with the mocked seam.
import '../api';

type RefreshHandler = () => Promise<string | null>;
const handler = jest.mocked(setRefreshHandler).mock.calls[0][0] as RefreshHandler;

const user: User = { id: 'user-1', email: 'driver@parkio.dev', status: 'ACTIVE', roles: ['USER'] };

const rotated: AuthResponse = {
  accessToken: 'new-access',
  tokenType: 'Bearer',
  accessTokenExpiresAt: '2026-07-01T12:00:00Z',
  refreshTokenExpiresAt: '2026-07-29T12:00:00Z',
  refreshToken: 'new-refresh',
  user,
};

const apiErrorBody = (code: string) => ({
  code,
  message: code,
  traceId: '',
  timestamp: '2026-07-05T00:00:00Z',
});

function seedSession() {
  tokenStorage.setTokens({ accessToken: 'old-access', refreshToken: 'old-refresh' });
  useAuthStore.getState().setSession(user);
}

describe('mobile refresh handler', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    (SecureStore as unknown as { __resetStore: () => void }).__resetStore();
    tokenStorage.clearTokens();
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

  it('returns null without a network call when no refresh token is stored', async () => {
    await expect(handler()).resolves.toBeNull();
    expect(mockRefresh).not.toHaveBeenCalled();
  });

  it('rotates both tokens and updates the session on success', async () => {
    seedSession();
    mockRefresh.mockResolvedValue(rotated);

    await expect(handler()).resolves.toBe('new-access');

    expect(mockRefresh).toHaveBeenCalledWith('old-refresh');
    expect(tokenStorage.getAccessToken()).toBe('new-access');
    expect(tokenStorage.getRefreshToken()).toBe('new-refresh');
    expect(useAuthStore.getState().isAuthenticated).toBe(true);
  });

  it('clears the stored session on a definitive 401', async () => {
    seedSession();
    mockRefresh.mockRejectedValue(new UnauthorizedError(apiErrorBody('INVALID_TOKEN')));

    await expect(handler()).resolves.toBeNull();

    expect(tokenStorage.getAccessToken()).toBeNull();
    expect(tokenStorage.getRefreshToken()).toBeNull();
    expect(useAuthStore.getState().isAuthenticated).toBe(false);
  });

  it('keeps the stored refresh token on a network failure (offline)', async () => {
    seedSession();
    mockRefresh.mockRejectedValue(new Error('Network Error'));

    await expect(handler()).resolves.toBeNull();

    expect(tokenStorage.getRefreshToken()).toBe('old-refresh');
    expect(useAuthStore.getState().isAuthenticated).toBe(true);
  });

  it('keeps the stored refresh token on a backend 5xx', async () => {
    seedSession();
    mockRefresh.mockRejectedValue(new ParkioApiError(503, apiErrorBody('SERVICE_UNAVAILABLE')));

    await expect(handler()).resolves.toBeNull();

    expect(tokenStorage.getRefreshToken()).toBe('old-refresh');
  });

  it('keeps the session for a suspended account so the suspended screen can show', async () => {
    seedSession();
    mockRefresh.mockRejectedValue(new AccountNotActiveError(apiErrorBody('ACCOUNT_NOT_ACTIVE')));

    await expect(handler()).resolves.toBeNull();

    expect(tokenStorage.getRefreshToken()).toBe('old-refresh');
    expect(useAuthStore.getState().isAuthenticated).toBe(true);
  });

  it('does not resurrect a session torn down while the refresh was in flight', async () => {
    seedSession();
    mockRefresh.mockImplementation(async () => {
      // Simulate logout completing while the network call is in flight.
      tokenStorage.clearTokens();
      useAuthStore.getState().clearSession();
      return rotated;
    });

    await expect(handler()).resolves.toBeNull();

    expect(tokenStorage.getAccessToken()).toBeNull();
    expect(useAuthStore.getState().isAuthenticated).toBe(false);
  });
});
