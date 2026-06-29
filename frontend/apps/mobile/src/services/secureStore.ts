import * as SecureStore from 'expo-secure-store';

/**
 * Thin, typed wrapper over `expo-secure-store` (Keychain on iOS, Keystore-backed
 * EncryptedSharedPreferences on Android).
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

async function getItem(key: SecureKey): Promise<string | null> {
  try {
    return await SecureStore.getItemAsync(KEYS[key]);
  } catch {
    // A corrupt/locked keystore must never crash boot — treat as "no value".
    return null;
  }
}

async function setItem(key: SecureKey, value: string): Promise<void> {
  await SecureStore.setItemAsync(KEYS[key], value);
}

async function removeItem(key: SecureKey): Promise<void> {
  await SecureStore.deleteItemAsync(KEYS[key]);
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
