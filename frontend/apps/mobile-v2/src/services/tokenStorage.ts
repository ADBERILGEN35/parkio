import type { StoredTokens, TokenStorage } from '@parkio/api-client';
import { secureStore } from './secureStore';

/**
 * Mobile {@link TokenStorage}.
 *
 * The shared api-client reads the access token *synchronously* inside its
 * request interceptor, but the secure keystore is *asynchronous*. Bridge:
 * both tokens are kept in memory (the synchronous source of truth) and every
 * write is mirrored to `expo-secure-store`. {@link hydrate} reloads the
 * in-memory cache on cold start so a returning user can be refreshed before
 * the first network call.
 *
 * Unlike web (HttpOnly cookie), native clients hold the raw refresh token:
 * the backend returns it in the login/refresh body for
 * `X-Parkio-Client: mobile` requests and we replay it in refresh/logout
 * bodies. It lives ONLY in the keystore and this cache.
 */
class SecureTokenStorage implements TokenStorage {
  private accessToken: string | null = null;
  private refreshToken: string | null = null;

  /** Synchronous — used by the api-client request interceptor on every call. */
  getAccessToken(): string | null {
    return this.accessToken;
  }

  /** Synchronous — read by the refresh handler to build the request body. */
  getRefreshToken(): string | null {
    return this.refreshToken;
  }

  setTokens(tokens: StoredTokens): void {
    this.accessToken = tokens.accessToken;
    if (tokens.refreshToken !== undefined) {
      this.refreshToken = tokens.refreshToken ?? null;
    }
    // Fire-and-forget mirror to the keystore; in-memory stays authoritative.
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

  /** Cold-start restore from the keystore into the synchronous cache. */
  async hydrate(): Promise<void> {
    const session = await secureStore.loadSession();
    this.accessToken = session.accessToken;
    this.refreshToken = session.refreshToken;
  }
}

export const tokenStorage = new SecureTokenStorage();
