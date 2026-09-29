/**
 * PA-03 SecureStore race / durability tests.
 * Uses an in-memory controllable backend — does not claim full Android process-death coverage.
 */

import {
  PersistenceError,
  SESSION_RECORD_VERSION,
  __resetSecureStoreCoordinatorForTests,
  __setSecureStoreBackendForTests,
  beginTerminalPersistenceGeneration,
  getSecureStoreGeneration,
  secureStore,
  type SecureStoreBackend,
} from '../secureStore';
import { tokenStorage } from '../tokenStorage';

class ControllableStore implements SecureStoreBackend {
  readonly map = new Map<string, string>();
  failSet = false;
  failDelete = false;
  setGate: Promise<void> | null = null;
  setRelease: (() => void) | null = null;
  setCalls = 0;
  deleteCalls = 0;

  async getItemAsync(key: string): Promise<string | null> {
    return this.map.get(key) ?? null;
  }

  async setItemAsync(key: string, value: string): Promise<void> {
    this.setCalls += 1;
    if (this.setGate) {
      await this.setGate;
    }
    if (this.failSet) {
      throw new Error('set_unavailable');
    }
    this.map.set(key, value);
  }

  async deleteItemAsync(key: string): Promise<void> {
    this.deleteCalls += 1;
    if (this.failDelete) {
      throw new Error('delete_unavailable');
    }
    this.map.delete(key);
  }

  holdNextSets(): void {
    this.setGate = new Promise((resolve) => {
      this.setRelease = resolve;
    });
  }

  releaseSets(): void {
    this.setRelease?.();
    this.setGate = null;
    this.setRelease = null;
  }
}

const SESSION_KEY = 'parkio.session.v1';
const LEGACY_ACCESS = 'parkio.accessToken';
const LEGACY_REFRESH = 'parkio.refreshToken';
const LEGACY_USER = 'parkio.userId';

describe('PA-03 secure session persistence', () => {
  let store: ControllableStore;

  beforeEach(async () => {
    store = new ControllableStore();
    __setSecureStoreBackendForTests(store);
    __resetSecureStoreCoordinatorForTests();
    await tokenStorage.clearDurable();
    store.map.clear();
    store.failSet = false;
    store.failDelete = false;
    store.setCalls = 0;
    store.deleteCalls = 0;
  });

  afterEach(() => {
    __setSecureStoreBackendForTests(null);
    __resetSecureStoreCoordinatorForTests();
  });

  async function flush(): Promise<void> {
    await new Promise((r) => setTimeout(r, 0));
    await new Promise((r) => setTimeout(r, 0));
  }

  it('TOKEN-A: login persist writes authoritative record before memory adopt', async () => {
    const result = await tokenStorage.persistSession({
      accessToken: 'access-1',
      refreshToken: 'refresh-1',
      userId: 'user-1',
    });
    expect(result.ok).toBe(true);
    expect(tokenStorage.getAccessToken()).toBe('access-1');
    expect(tokenStorage.getRefreshToken()).toBe('refresh-1');
    const raw = store.map.get(SESSION_KEY);
    expect(raw).toBeTruthy();
    const parsed = JSON.parse(raw as string);
    expect(parsed.version).toBe(SESSION_RECORD_VERSION);
    expect(parsed.accessToken).toBe('access-1');
    expect(parsed.refreshToken).toBe('refresh-1');
    expect(store.map.has(LEGACY_ACCESS)).toBe(false);
  });

  it('TOKEN-B: login persistence failure fails closed', async () => {
    store.failSet = true;
    const result = await tokenStorage.persistSession({
      accessToken: 'access-x',
      refreshToken: 'refresh-x',
      userId: 'user-x',
    });
    expect(result.ok).toBe(false);
    expect(tokenStorage.getAccessToken()).toBeNull();
    expect(tokenStorage.getRefreshToken()).toBeNull();
    expect(store.map.has(SESSION_KEY)).toBe(false);
  });

  it('TOKEN-C: refresh persist adopts only after durable write', async () => {
    await tokenStorage.persistSession({
      accessToken: 'a0',
      refreshToken: 'r0',
      userId: 'u0',
    });
    const gen = getSecureStoreGeneration();
    const result = await tokenStorage.persistSession({
      accessToken: 'a1',
      refreshToken: 'r1',
      userId: 'u0',
      expectedGeneration: gen,
    });
    expect(result.ok).toBe(true);
    expect(tokenStorage.getRefreshToken()).toBe('r1');
    expect(JSON.parse(store.map.get(SESSION_KEY) as string).refreshToken).toBe('r1');
  });

  it('TOKEN-D: refresh persistence failure fails closed', async () => {
    await tokenStorage.persistSession({
      accessToken: 'a0',
      refreshToken: 'r0',
      userId: 'u0',
    });
    store.failSet = true;
    const gen = getSecureStoreGeneration();
    const result = await tokenStorage.persistSession({
      accessToken: 'a1',
      refreshToken: 'r1',
      expectedGeneration: gen,
    });
    expect(result.ok).toBe(false);
    expect(tokenStorage.getAccessToken()).toBeNull();
    expect(tokenStorage.getRefreshToken()).toBeNull();
  });

  it('TOKEN-E: save pending → logout → save finishes → hydrate logged out', async () => {
    store.holdNextSets();
    const gen = getSecureStoreGeneration();
    const savePromise = tokenStorage.persistSession({
      accessToken: 'stale-access',
      refreshToken: 'stale-refresh',
      userId: 'stale-user',
      expectedGeneration: gen,
    });

    const clearPromise = tokenStorage.clearDurable();
    store.releaseSets();

    const saveResult = await savePromise;
    const clearResult = await clearPromise;
    expect(clearResult.ok).toBe(true);
    expect(saveResult.ok).toBe(false);
    if (!saveResult.ok) {
      expect(saveResult.error.code).toBe('stale_generation');
    }
    expect(tokenStorage.getAccessToken()).toBeNull();

    await tokenStorage.hydrate();
    expect(tokenStorage.getAccessToken()).toBeNull();
    expect(store.map.has(SESSION_KEY)).toBe(false);
  });

  it('TOKEN-F: logout wins when generation bumped before refresh save', async () => {
    await tokenStorage.persistSession({
      accessToken: 'a0',
      refreshToken: 'r0',
      userId: 'u0',
    });
    const genAtRefreshStart = getSecureStoreGeneration();
    beginTerminalPersistenceGeneration();
    const result = await tokenStorage.persistSession({
      accessToken: 'a-rot',
      refreshToken: 'r-rot',
      expectedGeneration: genAtRefreshStart,
    });
    expect(result.ok).toBe(false);
    expect(tokenStorage.getAccessToken()).toBeNull();
  });

  it('TOKEN-H: legacy complete triple migrates once', async () => {
    store.map.set(LEGACY_ACCESS, 'legacy-a');
    store.map.set(LEGACY_REFRESH, 'legacy-r');
    store.map.set(LEGACY_USER, 'legacy-u');

    await tokenStorage.hydrate();
    expect(tokenStorage.getAccessToken()).toBe('legacy-a');
    expect(tokenStorage.getRefreshToken()).toBe('legacy-r');
    expect(store.map.has(SESSION_KEY)).toBe(true);
    expect(store.map.has(LEGACY_ACCESS)).toBe(false);
    expect(store.map.has(LEGACY_REFRESH)).toBe(false);
    expect(store.map.has(LEGACY_USER)).toBe(false);

    await tokenStorage.hydrate();
    expect(tokenStorage.getAccessToken()).toBe('legacy-a');
  });

  it('TOKEN-I: legacy partial/corrupt clears fail-closed', async () => {
    store.map.set(LEGACY_ACCESS, 'only-access');
    await tokenStorage.hydrate();
    expect(tokenStorage.getAccessToken()).toBeNull();
    expect(store.map.has(LEGACY_ACCESS)).toBe(false);
  });

  it('TOKEN-J: SecureStore deletion failure is surfaced', async () => {
    await tokenStorage.persistSession({
      accessToken: 'a0',
      refreshToken: 'r0',
      userId: 'u0',
    });
    store.failDelete = true;
    const result = await tokenStorage.clearDurable();
    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.error.code).toBe('delete_failed');
    }
    expect(tokenStorage.getAccessToken()).toBeNull();
    expect(tokenStorage.getLastPersistenceError()?.code).toBe('delete_failed');
  });

  it('TOKEN-K: hydrate corrupt record → logged out', async () => {
    store.map.set(SESSION_KEY, '{not-json');
    await tokenStorage.hydrate();
    expect(tokenStorage.getAccessToken()).toBeNull();
    expect(store.map.has(SESSION_KEY)).toBe(false);
  });

  it('TOKEN-L: PersistenceError messages never embed token values', async () => {
    const err = new PersistenceError('storage_unavailable', 'set_unavailable');
    expect(err.message).not.toMatch(/access-|refresh-|Bearer/);
    expect(JSON.stringify(err)).not.toMatch(/access-|refresh-/);
  });

  it('serialized queue: clear after save leaves CLEARED', async () => {
    const save = secureStore.saveSession({
      accessToken: 'a',
      refreshToken: 'r',
      userId: 'u',
    });
    const clear = secureStore.clearSession();
    await save.catch(() => undefined);
    await clear;
    expect(store.map.has(SESSION_KEY)).toBe(false);
  });

  it('process-death model A: persistence pending then interrupt → no partial adopt', async () => {
    store.holdNextSets();
    const pending = tokenStorage.persistSession({
      accessToken: 'pending-a',
      refreshToken: 'pending-r',
      userId: 'pending-u',
    });
    // Simulate process death before set resolves: reset coordinator + memory without release.
    __resetSecureStoreCoordinatorForTests();
    tokenStorage.clearTokens();
    await flush();
    // Abandoned write must not leave mixed memory; disk may still be empty.
    expect(tokenStorage.getAccessToken()).toBeNull();
    store.releaseSets();
    await pending.catch(() => undefined);
  });
});
