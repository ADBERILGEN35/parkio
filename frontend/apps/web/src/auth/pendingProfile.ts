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
 *   logs. A full page reload drops the phone; a non-sensitive
 *   `needsPhoneReentry` flag may remain so the preparing screen can ask the
 *   user to re-enter the phone from Profile. Legacy payloads that still contain
 *   `phoneNumber` are scrubbed on every read/write (and set the re-entry flag).
 */
const STORAGE_KEY = 'parkio.pendingProfile';

/** Persisted shape — never include phoneNumber. */
interface PersistedPendingProfile {
  displayName?: string;
  /** True when a phone was captured (or scrubbed from legacy storage) but is not in memory. */
  needsPhoneReentry?: boolean;
}

export interface PendingProfile {
  displayName?: string;
  /** In-memory only for the current page session; not browser-persisted. */
  phoneNumber?: string;
  /** Non-sensitive hint that phone must be re-entered (e.g. after reload). */
  needsPhoneReentry?: boolean;
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
    if (!persisted.displayName && !persisted.needsPhoneReentry) {
      sessionStorage.removeItem(STORAGE_KEY);
      return;
    }
    const payload: PersistedPendingProfile = {};
    if (persisted.displayName) payload.displayName = persisted.displayName;
    if (persisted.needsPhoneReentry) payload.needsPhoneReentry = true;
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(payload));
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
    const needsPhoneReentry =
      record.needsPhoneReentry === true || hadLegacyPhone;
    const allowed = new Set(['displayName', 'needsPhoneReentry']);
    const extraKeys = Object.keys(record).filter((k) => !allowed.has(k));

    if (!displayName && !needsPhoneReentry) {
      sessionStorage.removeItem(STORAGE_KEY);
      return null;
    }

    const clean: PersistedPendingProfile = {};
    if (displayName) clean.displayName = displayName;
    if (needsPhoneReentry) clean.needsPhoneReentry = true;

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
  const needsPhoneReentry = Boolean(memoryPhoneNumber) || profile.needsPhoneReentry === true;
  writePersisted({
    displayName,
    needsPhoneReentry: needsPhoneReentry || undefined,
  });
}

export function getPendingProfile(): PendingProfile | null {
  const persisted = readAndScrubPersisted();
  const phoneNumber = memoryPhoneNumber;
  if (!persisted?.displayName && !phoneNumber && !persisted?.needsPhoneReentry) {
    return null;
  }
  return {
    displayName: persisted?.displayName,
    phoneNumber,
    // If phone is still in memory, re-entry is not needed yet.
    ...(Boolean(persisted?.needsPhoneReentry) && !phoneNumber
      ? { needsPhoneReentry: true as const }
      : {}),
  };
}

export function hasPendingProfile(profile: PendingProfile | null): profile is PendingProfile {
  return Boolean(
    profile && (profile.displayName || profile.phoneNumber || profile.needsPhoneReentry),
  );
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
