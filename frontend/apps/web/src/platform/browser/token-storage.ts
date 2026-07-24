import type { StoredTokens, TokenStorage } from '@parkio/api-client';

/** Web adapter: access tokens are memory-only and refresh tokens remain HttpOnly cookies. */
export class MemoryOnlyTokenStorage implements TokenStorage {
  private accessToken: string | null = null;

  getAccessToken(): string | null {
    return this.accessToken;
  }

  setTokens(tokens: StoredTokens): void {
    this.accessToken = tokens.accessToken;
  }

  clearTokens(): void {
    this.accessToken = null;
    localStorage.removeItem('parkio.accessToken');
    localStorage.removeItem('parkio.refreshToken');
  }
}
