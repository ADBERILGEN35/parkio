import type { StoredTokens, TokenStorage } from '@parkio/api-client';
import { secureStore } from './secureStore';

/**
 * Mobile {@link TokenStorage}.
 *
 * The shared api-client reads the access token *synchronously* inside its request
 * interceptor, but the secure keystore is *asynchronous*. We bridge that by
 * keeping both tokens in memory (the synchronous source of truth) and mirroring
 * every write to `expo-secure-store`. {@link hydrate} reloads the in-memory cache
 * from the keystore on cold start so a returning user can be refreshed before the
 * first network call.
 *
 * Unlike web (where the refresh token is an HttpOnly cookie the JS never sees),
 * native clients hold the raw refresh token here: the backend returns it in the
 * login/refresh body for `X-Parkio-Client: mobile` requests, and we replay it in
 * the refresh/logout request body. The token lives ONLY in the keystore and this
 * in-memory cache — never AsyncStorage, never navigation params, never logs.
 */
class SecureTokenStorage implements TokenStorage {
  private accessToken: string | null = null;
  private refreshToken: string | null = null;

  /** Synchronous — used by the api-client request interceptor on every call. */
  getAccessToken(): string | null {
    return this.accessToken;
  }

  /** Synchronous — read by the mobile refresh handler to build the request body. */
  getRefreshToken(): string | null {
    return this.refreshToken;
  }

  setTokens(tokens: StoredTokens): void {
    this.accessToken = tokens.accessToken;
    if (tokens.refreshToken !== undefined) {
      this.refreshToken = tokens.refreshToken;
    }
    // Fire-and-forget mirror to the keystore; the in-memory value is authoritative.
    void secureStore.saveSession({
      accessToken: tokens.accessToken,
      ...(tokens.refreshToken !== undefined ? { refreshToken: tokens.refreshToken } : {}),
    });
  }

  clearTokens(): void {
    this.accessToken = null;
    this.refreshToken = null;
    void secureStore.clearSession();
  }

  /** Load the persisted tokens into memory. Returns them for convenience. */
  async hydrate(): Promise<{ accessToken: string | null; refreshToken: string | null }> {
    const { accessToken, refreshToken } = await secureStore.loadSession();
    this.accessToken = accessToken;
    this.refreshToken = refreshToken;
    return { accessToken, refreshToken };
  }
}

export const tokenStorage = new SecureTokenStorage();
