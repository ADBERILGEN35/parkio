/**
 * Transient store for profile details captured during registration that the
 * backend's `POST /auth/register` does not accept (`displayName`, `phoneNumber`).
 * They are applied via `PATCH /users/me` from the preparing screen, then cleared.
 *
 * Ownership policy:
 * - Every pending profile is bound to the `user.id` returned by
 *   `POST /auth/register` (the authoritative identity; no client-side email
 *   equivalence rules). It may only be read back for that same user id via
 *   {@link getPendingProfileFor} / {@link claimPendingProfileFor}.
 * - A payload without an owner (written by a pre-ownership build), or one read
 *   for a different user id, is cleared and never applied.
 *
 * Persistence policy:
 * - `displayName` and the owner id may be kept in `sessionStorage` so a reload
 *   on `/preparing` can still complete the non-sensitive profile field.
 * - `phoneNumber` is held in module memory only for the current JS realm, tagged
 *   with its owner. It is never written to sessionStorage, localStorage,
 *   IndexedDB, cookies, URLs, or logs. A full page reload drops the phone; a
 *   non-sensitive `needsPhoneReentry` flag may remain so the preparing screen can
 *   ask the owner to re-enter the phone from Profile. Legacy payloads that still
 *   contain `phoneNumber` are scrubbed on every read/write (and set the re-entry
 *   flag).
 */
const STORAGE_KEY = 'parkio.pendingProfile';

/** Persisted shape — never include phoneNumber. */
interface PersistedPendingProfile {
  ownerUserId?: string;
  displayName?: string;
  /** True when a phone was captured (or scrubbed from legacy storage) but is not in memory. */
  needsPhoneReentry?: boolean;
}

export interface PendingProfileInput {
  displayName?: string;
  /** In-memory only for the current page session; not browser-persisted. */
  phoneNumber?: string;
  /** Non-sensitive hint that phone must be re-entered (e.g. after reload). */
  needsPhoneReentry?: boolean;
}

export interface PendingProfile extends PendingProfileInput {
  /** `user.id` of the account that registered these fields. */
  ownerUserId: string;
}

let memoryPhone: { ownerUserId: string; phoneNumber: string } | undefined;

function sanitizeText(value: unknown): string | undefined {
  if (typeof value !== 'string') return undefined;
  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : undefined;
}

/** Write only non-sensitive fields; drop any legacy phone key. */
function writePersisted(persisted: PersistedPendingProfile): void {
  try {
    if (!persisted.ownerUserId || (!persisted.displayName && !persisted.needsPhoneReentry)) {
      sessionStorage.removeItem(STORAGE_KEY);
      return;
    }
    const payload: PersistedPendingProfile = { ownerUserId: persisted.ownerUserId };
    if (persisted.displayName) payload.displayName = persisted.displayName;
    if (persisted.needsPhoneReentry) payload.needsPhoneReentry = true;
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(payload));
  } catch {
    // Non-fatal: registration still succeeds without the deferred profile save.
  }
}

/**
 * Read sessionStorage, strip legacy phoneNumber (and other unknown keys), and
 * rewrite a clean payload when scrubbing was needed. Ownerless payloads are
 * removed: they cannot be attributed to an account.
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
    const ownerUserId = sanitizeText(record.ownerUserId);
    const displayName = sanitizeText(record.displayName);
    const hadLegacyPhone = Object.prototype.hasOwnProperty.call(record, 'phoneNumber');
    const needsPhoneReentry = record.needsPhoneReentry === true || hadLegacyPhone;
    const allowed = new Set(['ownerUserId', 'displayName', 'needsPhoneReentry']);
    const extraKeys = Object.keys(record).filter((k) => !allowed.has(k));

    if (!ownerUserId || (!displayName && !needsPhoneReentry)) {
      sessionStorage.removeItem(STORAGE_KEY);
      return null;
    }

    const clean: PersistedPendingProfile = { ownerUserId };
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

/**
 * Store registration-captured fields for the account identified by
 * `ownerUserId` (the `user.id` from the register response). Replaces any
 * previous pending profile, including an in-memory phone. Without an owner
 * nothing is stored.
 */
export function setPendingProfile(profile: PendingProfileInput, ownerUserId: string): void {
  const owner = sanitizeText(ownerUserId);
  if (!owner) {
    clearPendingProfile();
    return;
  }
  const displayName = sanitizeText(profile.displayName);
  const phoneNumber = sanitizeText(profile.phoneNumber);
  memoryPhone = phoneNumber ? { ownerUserId: owner, phoneNumber } : undefined;
  const needsPhoneReentry = Boolean(phoneNumber) || profile.needsPhoneReentry === true;
  writePersisted({
    ownerUserId: owner,
    displayName,
    needsPhoneReentry: needsPhoneReentry || undefined,
  });
}

/** Current owned pending profile, regardless of which account is signed in. */
export function getPendingProfile(): PendingProfile | null {
  const persisted = readAndScrubPersisted();
  if (!persisted?.ownerUserId) {
    // Nothing attributable on disk; a stray in-memory phone must not survive.
    memoryPhone = undefined;
    return null;
  }
  const phoneNumber =
    memoryPhone?.ownerUserId === persisted.ownerUserId ? memoryPhone.phoneNumber : undefined;
  if (!persisted.displayName && !phoneNumber && !persisted.needsPhoneReentry) {
    return null;
  }
  return {
    ownerUserId: persisted.ownerUserId,
    displayName: persisted.displayName,
    phoneNumber,
    // If phone is still in memory, re-entry is not needed yet.
    ...(Boolean(persisted.needsPhoneReentry) && !phoneNumber
      ? { needsPhoneReentry: true as const }
      : {}),
  };
}

/**
 * Pending profile owned by `userId`. A pending profile owned by any other
 * account is cleared (it can no longer be applied safely in this tab).
 */
export function getPendingProfileFor(userId: string | null | undefined): PendingProfile | null {
  const pending = getPendingProfile();
  if (!pending) return null;
  if (!userId || pending.ownerUserId !== userId) {
    clearPendingProfile();
    return null;
  }
  return pending;
}

/**
 * Atomically take (read and clear) the pending profile owned by `userId`, so a
 * duplicate preparation run cannot apply it a second time.
 */
export function claimPendingProfileFor(userId: string | null | undefined): PendingProfile | null {
  const pending = getPendingProfileFor(userId);
  clearPendingProfile();
  return pending;
}

export function hasPendingProfile(profile: PendingProfile | null): profile is PendingProfile {
  return Boolean(
    profile && (profile.displayName || profile.phoneNumber || profile.needsPhoneReentry),
  );
}

export function clearPendingProfile(): void {
  memoryPhone = undefined;
  try {
    sessionStorage.removeItem(STORAGE_KEY);
  } catch {
    // ignore
  }
}

/** Test-only: drop in-memory phone without touching sessionStorage (reload sim). */
export function resetPendingProfileMemoryForTests(): void {
  memoryPhone = undefined;
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
