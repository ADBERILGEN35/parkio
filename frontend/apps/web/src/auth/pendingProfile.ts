/**
 * Transient store for profile details captured during registration that the
 * backend's `POST /auth/register` does not accept (`displayName`, `phoneNumber`).
 * They are applied via `PATCH /users/me` from the preparing screen, then cleared.
 *
 * Persistence policy:
 * - `displayName` may be kept in `sessionStorage` so a reload on `/preparing`
 *   can still complete the non-sensitive profile field.
 * - `phoneNumber` is held in module memory only for the current JS realm. It is
 *   never written to sessionStorage, localStorage, IndexedDB, cookies, URLs, or
 *   logs. A full page reload drops the phone; the user can add it later from
 *   Profile. Legacy payloads that still contain `phoneNumber` are scrubbed on
 *   every read/write.
 */
const STORAGE_KEY = 'parkio.pendingProfile';

/** Persisted shape — never include phoneNumber. */
interface PersistedPendingProfile {
  displayName?: string;
}

export interface PendingProfile {
  displayName?: string;
  /** In-memory only for the current page session; not browser-persisted. */
  phoneNumber?: string;
}

let memoryPhoneNumber: string | undefined;

function sanitizeDisplayName(value: unknown): string | undefined {
  if (typeof value !== 'string') return undefined;
  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : undefined;
}

function sanitizePhone(value: unknown): string | undefined {
  if (typeof value !== 'string') return undefined;
  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : undefined;
}

/** Write only non-sensitive fields; drop any legacy phone key. */
function writePersisted(persisted: PersistedPendingProfile): void {
  try {
    if (!persisted.displayName) {
      sessionStorage.removeItem(STORAGE_KEY);
      return;
    }
    sessionStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({ displayName: persisted.displayName } satisfies PersistedPendingProfile),
    );
  } catch {
    // Non-fatal: registration still succeeds without the deferred profile save.
  }
}

/**
 * Read sessionStorage, strip legacy phoneNumber (and other unknown sensitive
 * keys), and rewrite a clean payload when scrubbing was needed.
 */
function readAndScrubPersisted(): PersistedPendingProfile | null {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    if (!raw) return null;

    let parsed: unknown;
    try {
      parsed = JSON.parse(raw);
    } catch {
      sessionStorage.removeItem(STORAGE_KEY);
      return null;
    }

    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
      sessionStorage.removeItem(STORAGE_KEY);
      return null;
    }

    const record = parsed as Record<string, unknown>;
    const displayName = sanitizeDisplayName(record.displayName);
    const hadLegacyPhone = Object.prototype.hasOwnProperty.call(record, 'phoneNumber');
    const extraKeys = Object.keys(record).filter((k) => k !== 'displayName');

    if (!displayName) {
      sessionStorage.removeItem(STORAGE_KEY);
      return null;
    }

    const clean: PersistedPendingProfile = { displayName };
    if (hadLegacyPhone || extraKeys.length > 0) {
      // Rewrite so phone (and any stray keys) do not remain on disk.
      writePersisted(clean);
    }
    return clean;
  } catch {
    return null;
  }
}

export function setPendingProfile(profile: PendingProfile): void {
  const displayName = sanitizeDisplayName(profile.displayName);
  memoryPhoneNumber = sanitizePhone(profile.phoneNumber);
  writePersisted(displayName ? { displayName } : {});
}

export function getPendingProfile(): PendingProfile | null {
  const persisted = readAndScrubPersisted();
  const phoneNumber = memoryPhoneNumber;
  if (!persisted?.displayName && !phoneNumber) {
    return null;
  }
  return {
    displayName: persisted?.displayName,
    phoneNumber,
  };
}

export function hasPendingProfile(profile: PendingProfile | null): profile is PendingProfile {
  return Boolean(profile && (profile.displayName || profile.phoneNumber));
}

export function clearPendingProfile(): void {
  memoryPhoneNumber = undefined;
  try {
    sessionStorage.removeItem(STORAGE_KEY);
  } catch {
    // ignore
  }
}

/** Test-only: drop in-memory phone without touching sessionStorage (reload sim). */
export function resetPendingProfileMemoryForTests(): void {
  memoryPhoneNumber = undefined;
}

/** Test-only: whether sessionStorage still holds a phoneNumber key. */
export function pendingProfileStorageContainsPhoneForTests(): boolean {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    if (!raw) return false;
    const parsed = JSON.parse(raw) as Record<string, unknown>;
    return (
      parsed != null &&
      typeof parsed === 'object' &&
      Object.prototype.hasOwnProperty.call(parsed, 'phoneNumber')
    );
  } catch {
    return false;
  }
}
