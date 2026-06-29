export interface StoredTokens {
  accessToken: string;
  /**
   * Raw refresh token. Optional: the web transport keeps the refresh token in an
   * HttpOnly cookie and never sets this. Native mobile storage persists it here so
   * it can be replayed in the refresh/logout request body.
   */
  refreshToken?: string;
}

/**
 * Abstraction over where tokens are held. On web the refresh token lives in an
 * HttpOnly cookie (so only the access token is tracked here); native
 * implementations may also persist the refresh token via {@link StoredTokens}.
 */
export interface TokenStorage {
  getAccessToken(): string | null;
  setTokens(tokens: StoredTokens): void;
  clearTokens(): void;
}

export class MemoryTokenStorage implements TokenStorage {
  private accessToken: string | null = null;

  getAccessToken(): string | null {
    return this.accessToken;
  }

  setTokens(tokens: StoredTokens): void {
    this.accessToken = tokens.accessToken;
  }

  clearTokens(): void {
    this.accessToken = null;
  }
}
