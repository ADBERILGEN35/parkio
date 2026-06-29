import type { StoredTokens, TokenStorage } from '@parkio/api-client';
import { secureStore } from './secureStore';

/**
 * Mobile {@link TokenStorage}.
 *
 * The shared api-client reads the access token *synchronously* inside its request
 * interceptor, but the secure keystore is *asynchronous*. We bridge that by
 * keeping the access token in memory (the synchronous source of truth) and
 * mirroring every write to `expo-secure-store`. {@link hydrate} reloads the
 * in-memory cache from the keystore on cold start so a returning user is
 * optimistically authenticated before the first network call.
 *
 * The refresh token is held in the keystore as well; on the current backend it is
 * primarily an HttpOnly cookie (managed by the platform networking layer), and
 * this slot is wired so the architecture is ready if/when a bearer-refresh flow
 * is adopted for mobile. See `docs/mobile-architecture.md` → Token flow.
 */
class SecureTokenStorage implements TokenStorage {
  private accessToken: string | null = null;

  /** Synchronous — used by the api-client request interceptor on every call. */
  getAccessToken(): string | null {
    return this.accessToken;
  }

  setTokens(tokens: StoredTokens): void {
    this.accessToken = tokens.accessToken;
    // Fire-and-forget mirror to the keystore; in-memory value is authoritative.
    void secureStore.saveSession({ accessToken: tokens.accessToken });
  }

  clearTokens(): void {
    this.accessToken = null;
    void secureStore.clearSession();
  }

  /** Load the persisted access token into memory. Returns it for convenience. */
  async hydrate(): Promise<string | null> {
    const { accessToken } = await secureStore.loadSession();
    this.accessToken = accessToken;
    return accessToken;
  }
}

export const tokenStorage = new SecureTokenStorage();
