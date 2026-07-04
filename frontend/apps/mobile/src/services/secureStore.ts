import * as SecureStore from 'expo-secure-store';
import { Platform } from 'react-native';

/**
 * Thin, typed wrapper over the platform's secure storage.
 *
 * - **Native (Android/iOS)**: `expo-secure-store` (Keychain on iOS, Keystore-backed
 *   EncryptedSharedPreferences on Android). If the native module is missing or
 *   non-functional (e.g. a dev client built without it), writes fail closed with a
 *   clear error — tokens are never redirected to an insecure store.
 * - **Web (Metro web preview only)**: `expo-secure-store` ships an empty stub on web
 *   (no methods), so the adapter falls back to `localStorage` (or in-memory storage
 *   when `localStorage` is unavailable). This is a development convenience — the
 *   production web client is the SPA with its HttpOnly-cookie flow, not this app.
 *
 * This is the ONLY place sensitive values are persisted. Tokens and the user id
 * must never go to AsyncStorage. Keys are namespaced to avoid collisions.
 */
const KEYS = {
  accessToken: 'parkio.accessToken',
  refreshToken: 'parkio.refreshToken',
  userId: 'parkio.userId',
} as const;

export type SecureKey = keyof typeof KEYS;

interface StorageBackend {
  get(key: string): Promise<string | null>;
  set(key: string, value: string): Promise<void>;
  remove(key: string): Promise<void>;
}

const nativeBackend: StorageBackend = {
  get: (key) => SecureStore.getItemAsync(key),
  set: (key, value) => SecureStore.setItemAsync(key, value),
  remove: (key) => SecureStore.deleteItemAsync(key),
};

interface WebStorageLike {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

const memoryStore = new Map<string, string>();

let cachedWebStorage: WebStorageLike | null | undefined;

function getWebStorage(): WebStorageLike | null {
  if (cachedWebStorage === undefined) {
    try {
      const storage = (globalThis as { localStorage?: WebStorageLike }).localStorage ?? null;
      if (storage) {
        // Privacy mode / disabled storage throws on write — probe before trusting it.
        const probe = 'parkio.__storage_probe__';
        storage.setItem(probe, '1');
        storage.removeItem(probe);
      }
      cachedWebStorage = storage;
    } catch {
      cachedWebStorage = null;
    }
  }
  return cachedWebStorage;
}

const webBackend: StorageBackend = {
  async get(key) {
    const storage = getWebStorage();
    return storage ? storage.getItem(key) : (memoryStore.get(key) ?? null);
  },
  async set(key, value) {
    const storage = getWebStorage();
    if (storage) {
      storage.setItem(key, value);
    } else {
      memoryStore.set(key, value);
    }
  },
  async remove(key) {
    const storage = getWebStorage();
    if (storage) {
      storage.removeItem(key);
    } else {
      memoryStore.delete(key);
    }
  },
};

function hasSecureStoreApi(): boolean {
  return (
    typeof SecureStore.getItemAsync === 'function' &&
    typeof SecureStore.setItemAsync === 'function' &&
    typeof SecureStore.deleteItemAsync === 'function'
  );
}

async function isNativeSecureStoreUsable(): Promise<boolean> {
  if (!hasSecureStoreApi()) {
    return false;
  }
  // The JS wrappers above always exist; `isAvailableAsync` checks whether the
  // *native* module actually implements them (false on stale dev-client builds).
  if (typeof SecureStore.isAvailableAsync !== 'function') {
    return true;
  }
  try {
    return await SecureStore.isAvailableAsync();
  } catch {
    return false;
  }
}

let backendPromise: Promise<StorageBackend> | undefined;

function getBackend(): Promise<StorageBackend> {
  backendPromise ??= (async () => {
    if (Platform.OS === 'web') {
      return webBackend;
    }
    if (await isNativeSecureStoreUsable()) {
      return nativeBackend;
    }
    // Fail closed: on native, tokens go into the secure keystore or nowhere.
    throw new Error(
      'Secure storage is unavailable: the expo-secure-store native module is missing from this build. ' +
        'Rebuild the dev client / APK with expo-secure-store linked. Refusing to persist tokens insecurely.',
    );
  })();
  return backendPromise;
}

async function getItem(key: SecureKey): Promise<string | null> {
  try {
    const backend = await getBackend();
    return await backend.get(KEYS[key]);
  } catch {
    // A corrupt/locked keystore (or an unavailable one) must never crash boot —
    // treat as "no value"; the user simply has to sign in again.
    return null;
  }
}

async function setItem(key: SecureKey, value: string): Promise<void> {
  const backend = await getBackend();
  await backend.set(KEYS[key], value);
}

async function removeItem(key: SecureKey): Promise<void> {
  let backend: StorageBackend;
  try {
    backend = await getBackend();
  } catch {
    // No usable store means nothing was ever persisted — deleting is a no-op.
    // Only writes fail closed; logout/boot cleanup must not crash.
    return;
  }
  await backend.remove(KEYS[key]);
}

export interface PersistedSession {
  accessToken: string | null;
  refreshToken: string | null;
  userId: string | null;
}

export const secureStore = {
  getItem,
  setItem,
  removeItem,

  async loadSession(): Promise<PersistedSession> {
    const [accessToken, refreshToken, userId] = await Promise.all([
      getItem('accessToken'),
      getItem('refreshToken'),
      getItem('userId'),
    ]);
    return { accessToken, refreshToken, userId };
  },

  async saveSession(session: Partial<PersistedSession>): Promise<void> {
    const writes: Promise<void>[] = [];
    if (session.accessToken !== undefined) {
      writes.push(session.accessToken ? setItem('accessToken', session.accessToken) : removeItem('accessToken'));
    }
    if (session.refreshToken !== undefined) {
      writes.push(session.refreshToken ? setItem('refreshToken', session.refreshToken) : removeItem('refreshToken'));
    }
    if (session.userId !== undefined) {
      writes.push(session.userId ? setItem('userId', session.userId) : removeItem('userId'));
    }
    await Promise.all(writes);
  },

  async clearSession(): Promise<void> {
    await Promise.all([removeItem('accessToken'), removeItem('refreshToken'), removeItem('userId')]);
  },
};
