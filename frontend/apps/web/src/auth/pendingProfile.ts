/**
 * Transient store for profile details captured during registration that the
 * backend's `POST /auth/register` does not accept (`displayName`, `phoneNumber`).
 * They are stashed here while the account provisions, then persisted via
 * `PATCH /users/me` from the preparing screen and cleared. Backed by
 * `sessionStorage` so a reload on `/preparing` does not lose the data; all access
 * is guarded since storage can be unavailable (private mode / SSR).
 */
const STORAGE_KEY = 'parkio.pendingProfile';

export interface PendingProfile {
  displayName?: string;
  phoneNumber?: string;
}

export function setPendingProfile(profile: PendingProfile): void {
  try {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(profile));
  } catch {
    // Non-fatal: registration still succeeds without the deferred profile save.
  }
}

export function getPendingProfile(): PendingProfile | null {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as PendingProfile;
    return parsed && typeof parsed === 'object' ? parsed : null;
  } catch {
    return null;
  }
}

export function hasPendingProfile(profile: PendingProfile | null): profile is PendingProfile {
  return Boolean(profile && (profile.displayName || profile.phoneNumber));
}

export function clearPendingProfile(): void {
  try {
    sessionStorage.removeItem(STORAGE_KEY);
  } catch {
    // ignore
  }
}
