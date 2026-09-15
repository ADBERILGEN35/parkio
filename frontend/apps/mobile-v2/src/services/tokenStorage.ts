import type { StoredTokens, TokenStorage } from '@parkio/api-client';
import {
  PersistenceError,
  beginTerminalPersistenceGeneration,
  getSecureStoreGeneration,
  secureStore,
  type PersistedSessionRecord,
} from './secureStore';

export type TokenPersistenceResult =
  | { ok: true; record: PersistedSessionRecord }
  | { ok: false; error: PersistenceError };

/**
 * Mobile {@link TokenStorage} with durable SecureStore coordination (PA-03).
 *
 * Memory remains the synchronous interceptor source of truth AFTER a durable
 * write succeeds. Terminal logout clears memory immediately and invalidates
 * the persistence generation so late saves cannot resurrect the session.
 */
class SecureTokenStorage implements TokenStorage {
  private accessToken: string | null = null;
  private refreshToken: string | null = null;
  private userId: string | null = null;
  private lastError: PersistenceError | null = null;

  getAccessToken(): string | null {
    return this.accessToken;
  }

  getRefreshToken(): string | null {
    return this.refreshToken;
  }

  getUserId(): string | null {
    return this.userId;
  }

  getLastPersistenceError(): PersistenceError | null {
    return this.lastError;
  }

  /**
   * Synchronous memory update used only after durable persist, or for
   * interceptor-safe clears. Prefer {@link persistSession} / {@link clearDurable}.
   */
  setTokens(tokens: StoredTokens): void {
    this.accessToken = tokens.accessToken;
    if (tokens.refreshToken !== undefined) {
      this.refreshToken = tokens.refreshToken ?? null;
    }
  }

  clearTokens(): void {
    beginTerminalPersistenceGeneration();
    this.accessToken = null;
    this.refreshToken = null;
    this.userId = null;
    void secureStore.clearSession({ bumpGeneration: false }).catch((error) => {
      this.lastError =
        error instanceof PersistenceError
          ? error
          : new PersistenceError('delete_failed');
    });
  }

  /**
   * Durable persist then adopt into memory. Fails closed on storage errors /
   * stale generation. Pass {@link expectedGeneration} captured before any
   * network work so a logout during refresh cannot resurrect the session.
   */
  async persistSession(input: {
    accessToken: string;
    refreshToken: string;
    userId?: string | null;
    accessTokenExpiresAt?: number | null;
    expectedGeneration?: number;
  }): Promise<TokenPersistenceResult> {
    const expectedGeneration = input.expectedGeneration ?? getSecureStoreGeneration();
    if (expectedGeneration !== getSecureStoreGeneration()) {
      this.accessToken = null;
      this.refreshToken = null;
      this.userId = null;
      const error = new PersistenceError('stale_generation');
      this.lastError = error;
      return { ok: false, error };
    }
    try {
      const record = await secureStore.saveSession(
        {
          accessToken: input.accessToken,
          refreshToken: input.refreshToken,
          userId: input.userId ?? this.userId,
          accessTokenExpiresAt: input.accessTokenExpiresAt ?? null,
        },
        { expectedGeneration },
      );
      if (getSecureStoreGeneration() !== expectedGeneration) {
        this.accessToken = null;
        this.refreshToken = null;
        this.userId = null;
        await secureStore.clearSession();
        const error = new PersistenceError('stale_generation');
        this.lastError = error;
        return { ok: false, error };
      }
      this.accessToken = record.accessToken;
      this.refreshToken = record.refreshToken;
      this.userId = record.userId;
      this.lastError = null;
      return { ok: true, record };
    } catch (error) {
      const persistenceError =
        error instanceof PersistenceError
          ? error
          : new PersistenceError('storage_unavailable');
      this.lastError = persistenceError;
      this.accessToken = null;
      this.refreshToken = null;
      this.userId = null;
      return { ok: false, error: persistenceError };
    }
  }

  /**
   * Terminal local logout: invalidate writers, clear memory, await durable clear.
   */
  async clearDurable(): Promise<{ ok: true } | { ok: false; error: PersistenceError }> {
    beginTerminalPersistenceGeneration();
    this.accessToken = null;
    this.refreshToken = null;
    this.userId = null;
    try {
      await secureStore.clearSession({ bumpGeneration: false });
      this.lastError = null;
      return { ok: true };
    } catch (error) {
      const persistenceError =
        error instanceof PersistenceError
          ? error
          : new PersistenceError('delete_failed');
      this.lastError = persistenceError;
      return { ok: false, error: persistenceError };
    }
  }

  /** Cold-start restore from the authoritative record (with legacy migration). */
  async hydrate(): Promise<void> {
    try {
      const session = await secureStore.loadSession();
      if (!session) {
        this.accessToken = null;
        this.refreshToken = null;
        this.userId = null;
        return;
      }
      this.accessToken = session.accessToken;
      this.refreshToken = session.refreshToken;
      this.userId = session.userId;
      this.lastError = null;
    } catch (error) {
      this.lastError =
        error instanceof PersistenceError
          ? error
          : new PersistenceError('storage_unavailable');
      this.accessToken = null;
      this.refreshToken = null;
      this.userId = null;
      await secureStore.clearSession().catch(() => undefined);
    }
  }
}

export const tokenStorage = new SecureTokenStorage();
