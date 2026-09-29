/**
 * Logout orchestration order proof (behavioral, not source-string).
 */

import { QueryClient } from '@tanstack/react-query';
import {
  clearPendingAuthGateIntent,
  peekPendingAuthGateIntent,
  setPendingAuthGateIntent,
} from '@/features/auth/authGateIntents';
import { registerSessionQueryClient } from '@/data/sessionQueryCleanup';
import { meKeys, parkingKeys } from '@/data/keys';
import { useAuthStore } from '@/state/authStore';
import {
  PersistenceError,
  __resetSecureStoreCoordinatorForTests,
  __setSecureStoreBackendForTests,
  type SecureStoreBackend,
} from '../secureStore';
import { tokenStorage } from '../tokenStorage';

jest.mock('../api', () => ({
  authApi: {
    logout: jest.fn(async () => undefined),
    logoutAll: jest.fn(async () => undefined),
    refresh: jest.fn(),
  },
}));

import { signOut } from '../auth';
import { authApi } from '../api';

class MemoryBackend implements SecureStoreBackend {
  map = new Map<string, string>();
  async getItemAsync(key: string) {
    return this.map.get(key) ?? null;
  }
  async setItemAsync(key: string, value: string) {
    this.map.set(key, value);
  }
  async deleteItemAsync(key: string) {
    this.map.delete(key);
  }
}

describe('signOut orchestration (PA-02/PA-03)', () => {
  let backend: MemoryBackend;
  let client: QueryClient;
  let unregister: () => void;

  beforeEach(async () => {
    backend = new MemoryBackend();
    __setSecureStoreBackendForTests(backend);
    __resetSecureStoreCoordinatorForTests();
    client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    unregister = registerSessionQueryClient(client);
    clearPendingAuthGateIntent();
    useAuthStore.setState({ status: 'anonymous', user: null, sessionEpoch: 0 });

    await tokenStorage.persistSession({
      accessToken: 'access-live',
      refreshToken: 'refresh-live',
      userId: 'user-live',
    });
    useAuthStore.getState().setSession({
      id: 'user-live',
      email: 'live@parkio.dev',
      roles: ['USER'],
      status: 'ACTIVE',
      emailVerified: true,
    } as never);
    client.setQueryData(meKeys.profile(), { id: 'user-live' });
    client.setQueryData(parkingKeys.nearby({ lat: 1, lng: 2 }), [{ id: 'private-spot' }]);
    setPendingAuthGateIntent({ intent: 'contribute' });
  });

  afterEach(() => {
    unregister();
    __setSecureStoreBackendForTests(null);
    __resetSecureStoreCoordinatorForTests();
    clearPendingAuthGateIntent();
    jest.clearAllMocks();
  });

  it('invalidates auth ability, clears private queries, AuthGate intents, then durable store', async () => {
    const events: string[] = [];
    const originalCancel = client.cancelQueries.bind(client);
    jest.spyOn(client, 'cancelQueries').mockImplementation(async (...args) => {
      events.push('cancelQueries');
      return originalCancel(...args);
    });
    const originalRemove = client.removeQueries.bind(client);
    jest.spyOn(client, 'removeQueries').mockImplementation((...args) => {
      events.push('removeQueries');
      return originalRemove(...args);
    });

    (authApi.logout as jest.Mock).mockImplementation(async () => {
      events.push('serverLogout');
      expect(tokenStorage.getAccessToken()).toBeNull();
      expect(useAuthStore.getState().status).toBe('anonymous');
      expect(peekPendingAuthGateIntent()).toBeNull();
      expect(client.getQueryData(meKeys.profile())).toBeUndefined();
    });

    await signOut();

    expect(tokenStorage.getAccessToken()).toBeNull();
    expect(tokenStorage.getRefreshToken()).toBeNull();
    expect(useAuthStore.getState().status).toBe('anonymous');
    expect(peekPendingAuthGateIntent()).toBeNull();
    expect(client.getQueryData(meKeys.profile())).toBeUndefined();
    expect(client.getQueryData(parkingKeys.nearby({ lat: 1, lng: 2 }))).toBeUndefined();
    expect(backend.map.has('parkio.session.v1')).toBe(false);
    expect(events.indexOf('cancelQueries')).toBeGreaterThanOrEqual(0);
    expect(events.indexOf('removeQueries')).toBeGreaterThanOrEqual(0);
    expect(events.indexOf('serverLogout')).toBeGreaterThan(events.indexOf('cancelQueries'));
  });

  it('completes local logout when server revoke fails', async () => {
    (authApi.logout as jest.Mock).mockRejectedValueOnce(new Error('offline'));
    await signOut();
    expect(useAuthStore.getState().status).toBe('anonymous');
    expect(tokenStorage.getAccessToken()).toBeNull();
    expect(backend.map.has('parkio.session.v1')).toBe(false);
  });

  it('surfaces delete failure without restoring memory tokens', async () => {
    const failing: SecureStoreBackend = {
      getItemAsync: async (key) => backend.getItemAsync(key),
      setItemAsync: async (key, value) => backend.setItemAsync(key, value),
      deleteItemAsync: async () => {
        throw new Error('delete_unavailable');
      },
    };
    __setSecureStoreBackendForTests(failing);
    await signOut();
    expect(tokenStorage.getAccessToken()).toBeNull();
    expect(useAuthStore.getState().status).toBe('anonymous');
    expect(tokenStorage.getLastPersistenceError()).toBeInstanceOf(PersistenceError);
  });
});
