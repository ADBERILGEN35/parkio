import * as SecureStore from 'expo-secure-store';

/**
 * Keystore-backed session persistence. Tokens live ONLY here and in the
 * in-memory `tokenStorage` cache — never in AsyncStorage/jsonStore, navigation
 * params or logs.
 */
const ACCESS_TOKEN_KEY = 'parkio.accessToken';
const REFRESH_TOKEN_KEY = 'parkio.refreshToken';
const USER_ID_KEY = 'parkio.userId';

export interface PersistedSession {
  accessToken?: string | null;
  refreshToken?: string | null;
  userId?: string | null;
}

async function setOrDelete(key: string, value: string | null | undefined): Promise<void> {
  if (value === undefined) {
    return;
  }
  if (value === null) {
    await SecureStore.deleteItemAsync(key);
    return;
  }
  await SecureStore.setItemAsync(key, value);
}

export const secureStore = {
  async saveSession(session: PersistedSession): Promise<void> {
    try {
      await Promise.all([
        setOrDelete(ACCESS_TOKEN_KEY, session.accessToken),
        setOrDelete(REFRESH_TOKEN_KEY, session.refreshToken),
        setOrDelete(USER_ID_KEY, session.userId),
      ]);
    } catch (error) {
      console.warn('[secureStore] failed to persist session', error);
    }
  },

  async loadSession(): Promise<{
    accessToken: string | null;
    refreshToken: string | null;
    userId: string | null;
  }> {
    try {
      const [accessToken, refreshToken, userId] = await Promise.all([
        SecureStore.getItemAsync(ACCESS_TOKEN_KEY),
        SecureStore.getItemAsync(REFRESH_TOKEN_KEY),
        SecureStore.getItemAsync(USER_ID_KEY),
      ]);
      return { accessToken, refreshToken, userId };
    } catch {
      return { accessToken: null, refreshToken: null, userId: null };
    }
  },

  async clearSession(): Promise<void> {
    try {
      await Promise.all([
        SecureStore.deleteItemAsync(ACCESS_TOKEN_KEY),
        SecureStore.deleteItemAsync(REFRESH_TOKEN_KEY),
        SecureStore.deleteItemAsync(USER_ID_KEY),
      ]);
    } catch (error) {
      console.warn('[secureStore] failed to clear session', error);
    }
  },
};
