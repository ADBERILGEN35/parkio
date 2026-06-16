export interface StoredTokens {
  accessToken: string;
}

/** Abstraction over where access tokens are held. Refresh tokens live in HttpOnly cookies. */
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
