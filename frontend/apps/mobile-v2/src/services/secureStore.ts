import * as ExpoSecureStore from 'expo-secure-store';

/**
 * Keystore-backed session persistence (PA-03).
 *
 * Authoritative model: ONE versioned JSON record under SESSION_RECORD_KEY.
 * Legacy triple-key layout is migrated once then deleted.
 *
 * All mutations run through a serialized queue keyed by a monotonic generation.
 * Terminal clear bumps generation first so a late save cannot resurrect state.
 */

const SESSION_RECORD_KEY = 'parkio.session.v1';
/** Legacy keys (pre-serialized record). Migrated then removed. */
const LEGACY_ACCESS_TOKEN_KEY = 'parkio.accessToken';
const LEGACY_REFRESH_TOKEN_KEY = 'parkio.refreshToken';
const LEGACY_USER_ID_KEY = 'parkio.userId';

export const SESSION_RECORD_VERSION = 1 as const;

export type PersistedSessionRecord = {
  version: typeof SESSION_RECORD_VERSION;
  accessToken: string;
  refreshToken: string;
  userId: string | null;
  accessTokenExpiresAt: number | null;
  sessionGeneration: number;
};

export type PersistedSessionPatch = {
  accessToken?: string | null;
  refreshToken?: string | null;
  userId?: string | null;
  accessTokenExpiresAt?: number | null;
};

export type SecureStoreBackend = {
  getItemAsync: (key: string) => Promise<string | null>;
  setItemAsync: (key: string, value: string) => Promise<void>;
  deleteItemAsync: (key: string) => Promise<void>;
};

export class PersistenceError extends Error {
  readonly code: 'storage_unavailable' | 'corrupt_record' | 'migration_failed' | 'delete_failed' | 'stale_generation';

  constructor(code: PersistenceError['code'], message?: string) {
    super(message ?? code);
    this.name = 'PersistenceError';
    this.code = code;
  }
}

let backend: SecureStoreBackend = ExpoSecureStore;
let generation = 0;
let queue: Promise<unknown> = Promise.resolve();

function enqueue<T>(op: () => Promise<T>): Promise<T> {
  const run = queue.then(op, op);
  queue = run.then(
    () => undefined,
    () => undefined,
  );
  return run;
}

export function __setSecureStoreBackendForTests(next: SecureStoreBackend | null): void {
  backend = next ?? ExpoSecureStore;
}

export function __resetSecureStoreCoordinatorForTests(): void {
  generation = 0;
  queue = Promise.resolve();
}

export function getSecureStoreGeneration(): number {
  return generation;
}

/** Invalidate all pending/stale writers. Call at the start of terminal logout. */
export function beginTerminalPersistenceGeneration(): number {
  generation += 1;
  return generation;
}

function parseRecord(raw: string | null): PersistedSessionRecord | null {
  if (raw == null || raw === '') {
    return null;
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    throw new PersistenceError('corrupt_record');
  }
  if (parsed == null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new PersistenceError('corrupt_record');
  }
  const obj = parsed as Record<string, unknown>;
  if (obj.version !== SESSION_RECORD_VERSION) {
    throw new PersistenceError('corrupt_record', 'unsupported_version');
  }
  if (typeof obj.accessToken !== 'string' || obj.accessToken.length === 0) {
    throw new PersistenceError('corrupt_record', 'missing_access');
  }
  if (typeof obj.refreshToken !== 'string' || obj.refreshToken.length === 0) {
    throw new PersistenceError('corrupt_record', 'missing_refresh');
  }
  if (obj.userId != null && typeof obj.userId !== 'string') {
    throw new PersistenceError('corrupt_record', 'invalid_user');
  }
  const expires = obj.accessTokenExpiresAt;
  if (expires != null && (typeof expires !== 'number' || !Number.isFinite(expires))) {
    throw new PersistenceError('corrupt_record', 'invalid_expiry');
  }
  const sessionGeneration = obj.sessionGeneration;
  if (typeof sessionGeneration !== 'number' || !Number.isFinite(sessionGeneration)) {
    throw new PersistenceError('corrupt_record', 'invalid_generation');
  }
  return {
    version: SESSION_RECORD_VERSION,
    accessToken: obj.accessToken,
    refreshToken: obj.refreshToken,
    userId: (obj.userId as string | null | undefined) ?? null,
    accessTokenExpiresAt: (expires as number | null | undefined) ?? null,
    sessionGeneration,
  };
}

async function deleteKey(key: string): Promise<void> {
  try {
    await backend.deleteItemAsync(key);
  } catch (error) {
    throw new PersistenceError('delete_failed', error instanceof Error ? error.message : 'delete_failed');
  }
}

async function clearLegacyKeys(): Promise<void> {
  await Promise.all([
    deleteKey(LEGACY_ACCESS_TOKEN_KEY),
    deleteKey(LEGACY_REFRESH_TOKEN_KEY),
    deleteKey(LEGACY_USER_ID_KEY),
  ]);
}

async function readLegacy(): Promise<{
  accessToken: string | null;
  refreshToken: string | null;
  userId: string | null;
}> {
  try {
    const [accessToken, refreshToken, userId] = await Promise.all([
      backend.getItemAsync(LEGACY_ACCESS_TOKEN_KEY),
      backend.getItemAsync(LEGACY_REFRESH_TOKEN_KEY),
      backend.getItemAsync(LEGACY_USER_ID_KEY),
    ]);
    return { accessToken, refreshToken, userId };
  } catch (error) {
    throw new PersistenceError(
      'storage_unavailable',
      error instanceof Error ? error.message : 'storage_unavailable',
    );
  }
}

async function writeRecord(record: PersistedSessionRecord, expectedGeneration: number): Promise<void> {
  if (expectedGeneration !== generation) {
    throw new PersistenceError('stale_generation');
  }
  try {
    await backend.setItemAsync(SESSION_RECORD_KEY, JSON.stringify(record));
  } catch (error) {
    throw new PersistenceError(
      'storage_unavailable',
      error instanceof Error ? error.message : 'storage_unavailable',
    );
  }
  if (expectedGeneration !== generation) {
    await deleteKey(SESSION_RECORD_KEY);
    throw new PersistenceError('stale_generation');
  }
}

export const secureStore = {
  /**
   * Persist a complete session record. Rejects on stale generation or storage failure.
   * Does not log token values.
   */
  async saveSession(
    patch: Required<Pick<PersistedSessionPatch, 'accessToken' | 'refreshToken'>> &
      PersistedSessionPatch,
    options?: { expectedGeneration?: number },
  ): Promise<PersistedSessionRecord> {
    const expectedGeneration = options?.expectedGeneration ?? generation;
    return enqueue(async () => {
      if (expectedGeneration !== generation) {
        throw new PersistenceError('stale_generation');
      }
      if (!patch.accessToken || !patch.refreshToken) {
        throw new PersistenceError('corrupt_record', 'incomplete_session');
      }
      const record: PersistedSessionRecord = {
        version: SESSION_RECORD_VERSION,
        accessToken: patch.accessToken,
        refreshToken: patch.refreshToken,
        userId: patch.userId ?? null,
        accessTokenExpiresAt: patch.accessTokenExpiresAt ?? null,
        sessionGeneration: expectedGeneration,
      };
      await writeRecord(record, expectedGeneration);
      await clearLegacyKeys();
      return record;
    });
  },

  /**
   * Load authoritative session. Migrates complete legacy triples once.
   * Corrupt / partial legacy → clears and returns null (fail-closed).
   */
  async loadSession(): Promise<PersistedSessionRecord | null> {
    return enqueue(async () => {
      try {
        const raw = await backend.getItemAsync(SESSION_RECORD_KEY);
        if (raw) {
          return parseRecord(raw);
        }
      } catch (error) {
        if (error instanceof PersistenceError && error.code === 'corrupt_record') {
          await deleteKey(SESSION_RECORD_KEY);
          await clearLegacyKeys();
          return null;
        }
        if (error instanceof PersistenceError) {
          throw error;
        }
        throw new PersistenceError(
          'storage_unavailable',
          error instanceof Error ? error.message : 'storage_unavailable',
        );
      }

      const legacy = await readLegacy();
      const hasAny = Boolean(legacy.accessToken || legacy.refreshToken || legacy.userId);
      const hasComplete = Boolean(legacy.accessToken && legacy.refreshToken);
      if (!hasAny) {
        return null;
      }
      if (!hasComplete) {
        await clearLegacyKeys();
        return null;
      }
      const migrated: PersistedSessionRecord = {
        version: SESSION_RECORD_VERSION,
        accessToken: legacy.accessToken as string,
        refreshToken: legacy.refreshToken as string,
        userId: legacy.userId,
        accessTokenExpiresAt: null,
        sessionGeneration: generation,
      };
      try {
        await writeRecord(migrated, generation);
        await clearLegacyKeys();
        return migrated;
      } catch (error) {
        await clearLegacyKeys();
        await deleteKey(SESSION_RECORD_KEY).catch(() => undefined);
        throw error instanceof PersistenceError
          ? error
          : new PersistenceError('migration_failed');
      }
    });
  },

  /** Terminal clear: bumps generation (unless already bumped), then deletes record + legacy keys. */
  async clearSession(options?: { bumpGeneration?: boolean }): Promise<void> {
    if (options?.bumpGeneration !== false) {
      beginTerminalPersistenceGeneration();
    }
    return enqueue(async () => {
      try {
        await deleteKey(SESSION_RECORD_KEY);
        await clearLegacyKeys();
      } catch (error) {
        if (error instanceof PersistenceError) {
          throw error;
        }
        throw new PersistenceError('delete_failed');
      }
    });
  },
};
