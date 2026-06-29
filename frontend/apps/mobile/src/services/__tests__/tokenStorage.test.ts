import * as SecureStore from 'expo-secure-store';
import { tokenStorage } from '../tokenStorage';

const flush = () => new Promise((resolve) => setTimeout(resolve, 0));

describe('SecureTokenStorage', () => {
  beforeEach(() => {
    (SecureStore as unknown as { __resetStore: () => void }).__resetStore();
    tokenStorage.clearTokens();
  });

  it('exposes the access token synchronously after setTokens', () => {
    tokenStorage.setTokens({ accessToken: 'access-1' });
    expect(tokenStorage.getAccessToken()).toBe('access-1');
  });

  it('mirrors the access token into the secure keystore', async () => {
    tokenStorage.setTokens({ accessToken: 'access-2' });
    await flush();
    await expect(SecureStore.getItemAsync('parkio.accessToken')).resolves.toBe('access-2');
  });

  it('hydrates the in-memory token from the keystore on cold start', async () => {
    await SecureStore.setItemAsync('parkio.accessToken', 'persisted-token');
    const hydrated = await tokenStorage.hydrate();
    expect(hydrated).toBe('persisted-token');
    expect(tokenStorage.getAccessToken()).toBe('persisted-token');
  });

  it('clears memory and keystore on logout', async () => {
    tokenStorage.setTokens({ accessToken: 'access-3' });
    await flush();
    tokenStorage.clearTokens();
    await flush();
    expect(tokenStorage.getAccessToken()).toBeNull();
    await expect(SecureStore.getItemAsync('parkio.accessToken')).resolves.toBeNull();
  });
});
