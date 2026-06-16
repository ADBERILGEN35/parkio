import type { StoredTokens, TokenStorage } from '@parkio/api-client';

/** Web implementation — access token is memory-only; refresh token is an HttpOnly cookie. */
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

export const webTokenStorage = new MemoryOnlyTokenStorage();
