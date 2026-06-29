import * as SecureStore from 'expo-secure-store';
import { tokenStorage } from '../tokenStorage';

const flush = () => new Promise((resolve) => setTimeout(resolve, 0));

describe('SecureTokenStorage', () => {
  beforeEach(() => {
    (SecureStore as unknown as { __resetStore: () => void }).__resetStore();
    tokenStorage.clearTokens();
  });

  it('exposes the access token synchronously after setTokens', () => {
    tokenStorage.setTokens({ accessToken: 'access-1', refreshToken: 'refresh-1' });
    expect(tokenStorage.getAccessToken()).toBe('access-1');
    expect(tokenStorage.getRefreshToken()).toBe('refresh-1');
  });

  it('mirrors access and refresh tokens into the secure keystore', async () => {
    tokenStorage.setTokens({ accessToken: 'access-2', refreshToken: 'refresh-2' });
    await flush();
    await expect(SecureStore.getItemAsync('parkio.accessToken')).resolves.toBe('access-2');
    await expect(SecureStore.getItemAsync('parkio.refreshToken')).resolves.toBe('refresh-2');
  });

  it('hydrates the in-memory tokens from the keystore on cold start', async () => {
    await SecureStore.setItemAsync('parkio.accessToken', 'persisted-token');
    await SecureStore.setItemAsync('parkio.refreshToken', 'persisted-refresh');
    const hydrated = await tokenStorage.hydrate();
    expect(hydrated).toEqual({ accessToken: 'persisted-token', refreshToken: 'persisted-refresh' });
    expect(tokenStorage.getAccessToken()).toBe('persisted-token');
    expect(tokenStorage.getRefreshToken()).toBe('persisted-refresh');
  });

  it('clears memory and keystore on logout', async () => {
    tokenStorage.setTokens({ accessToken: 'access-3', refreshToken: 'refresh-3' });
    await flush();
    tokenStorage.clearTokens();
    await flush();
    expect(tokenStorage.getAccessToken()).toBeNull();
    expect(tokenStorage.getRefreshToken()).toBeNull();
    await expect(SecureStore.getItemAsync('parkio.accessToken')).resolves.toBeNull();
    await expect(SecureStore.getItemAsync('parkio.refreshToken')).resolves.toBeNull();
  });
});
