import type { StoredTokens, TokenStorage } from '@parkio/api-client';

const ACCESS_KEY = 'parkio.accessToken';
const REFRESH_KEY = 'parkio.refreshToken';

/** Web implementation — persists tokens in localStorage. */
export class LocalStorageTokenStorage implements TokenStorage {
  getAccessToken(): string | null {
    return localStorage.getItem(ACCESS_KEY);
  }

  getRefreshToken(): string | null {
    return localStorage.getItem(REFRESH_KEY);
  }

  setTokens(tokens: StoredTokens): void {
    localStorage.setItem(ACCESS_KEY, tokens.accessToken);
    localStorage.setItem(REFRESH_KEY, tokens.refreshToken);
  }

  clearTokens(): void {
    localStorage.removeItem(ACCESS_KEY);
    localStorage.removeItem(REFRESH_KEY);
  }
}

export const webTokenStorage = new LocalStorageTokenStorage();
