import * as SecureStore from 'expo-secure-store';
import { secureStore } from '../secureStore';

/**
 * Loads a fresh secureStore module instance with the given platform and
 * expo-secure-store implementation, bypassing the global jest.setup mock.
 * Needed because the adapter memoizes its backend choice per module instance.
 */
function loadSecureStoreWith(platform: string, impl: Record<string, unknown>) {
  jest.resetModules();
  jest.doMock('react-native', () => ({ Platform: { OS: platform } }));
  jest.doMock('expo-secure-store', () => impl);
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  const mod = require('../secureStore') as typeof import('../secureStore');
  jest.dontMock('react-native');
  jest.dontMock('expo-secure-store');
  return mod.secureStore;
}

function createFakeLocalStorage() {
  const map = new Map<string, string>();
  return {
    getItem: (key: string) => map.get(key) ?? null,
    setItem: (key: string, value: string) => {
      map.set(key, value);
    },
    removeItem: (key: string) => {
      map.delete(key);
    },
  };
}

describe('secureStore native backend', () => {
  beforeEach(() => {
    (SecureStore as unknown as { __resetStore: () => void }).__resetStore();
  });

  it('saves, loads and deletes values via expo-secure-store', async () => {
    await secureStore.setItem('accessToken', 'native-token');
    expect(SecureStore.setItemAsync).toHaveBeenCalledWith('parkio.accessToken', 'native-token');
    await expect(secureStore.getItem('accessToken')).resolves.toBe('native-token');

    await secureStore.removeItem('accessToken');
    expect(SecureStore.deleteItemAsync).toHaveBeenCalledWith('parkio.accessToken');
    await expect(secureStore.getItem('accessToken')).resolves.toBeNull();
  });

  it('clearSession resolves when no token exists', async () => {
    await expect(secureStore.clearSession()).resolves.toBeUndefined();
    await expect(secureStore.loadSession()).resolves.toEqual({
      accessToken: null,
      refreshToken: null,
      userId: null,
    });
  });

  it('round-trips a full session through saveSession/loadSession', async () => {
    await secureStore.saveSession({ accessToken: 'a', refreshToken: 'r', userId: 'u' });
    await expect(secureStore.loadSession()).resolves.toEqual({
      accessToken: 'a',
      refreshToken: 'r',
      userId: 'u',
    });
    await secureStore.clearSession();
    await expect(secureStore.loadSession()).resolves.toEqual({
      accessToken: null,
      refreshToken: null,
      userId: null,
    });
  });
});

describe('secureStore web fallback', () => {
  afterEach(() => {
    delete (globalThis as { localStorage?: unknown }).localStorage;
  });

  it('saves, loads and deletes via localStorage when available (empty native stub)', async () => {
    const fake = createFakeLocalStorage();
    (globalThis as { localStorage?: unknown }).localStorage = fake;
    // On web, expo-secure-store resolves to an empty object — no native methods.
    const webStore = loadSecureStoreWith('web', {});

    await webStore.setItem('accessToken', 'web-token');
    expect(fake.getItem('parkio.accessToken')).toBe('web-token');
    await expect(webStore.getItem('accessToken')).resolves.toBe('web-token');

    await webStore.removeItem('accessToken');
    expect(fake.getItem('parkio.accessToken')).toBeNull();
    await expect(webStore.getItem('accessToken')).resolves.toBeNull();
  });

  it('falls back to in-memory storage when localStorage is unavailable', async () => {
    const webStore = loadSecureStoreWith('web', {});

    await webStore.setItem('refreshToken', 'memory-token');
    await expect(webStore.getItem('refreshToken')).resolves.toBe('memory-token');

    await webStore.removeItem('refreshToken');
    await expect(webStore.getItem('refreshToken')).resolves.toBeNull();
  });

  it('clearSession does not crash when no token exists', async () => {
    const webStore = loadSecureStoreWith('web', {});
    await expect(webStore.clearSession()).resolves.toBeUndefined();
  });
});

describe('secureStore on native without a usable secure store', () => {
  it('fails closed on writes with a clear error', async () => {
    const brokenStore = loadSecureStoreWith('android', {});
    await expect(brokenStore.setItem('accessToken', 'token')).rejects.toThrow(
      /Secure storage is unavailable/,
    );
  });

  it('treats reads as missing values and lets cleanup succeed', async () => {
    const brokenStore = loadSecureStoreWith('android', {});
    await expect(brokenStore.getItem('accessToken')).resolves.toBeNull();
    await expect(brokenStore.clearSession()).resolves.toBeUndefined();
    await expect(brokenStore.loadSession()).resolves.toEqual({
      accessToken: null,
      refreshToken: null,
      userId: null,
    });
  });

  it('respects isAvailableAsync=false from the native module', async () => {
    const brokenStore = loadSecureStoreWith('android', {
      getItemAsync: jest.fn(),
      setItemAsync: jest.fn(),
      deleteItemAsync: jest.fn(),
      isAvailableAsync: jest.fn(async () => false),
    });
    await expect(brokenStore.setItem('accessToken', 'token')).rejects.toThrow(
      /Secure storage is unavailable/,
    );
  });
});
