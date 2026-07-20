import { readJson, removeJson, writeJson } from '@/services/jsonStore';
import { usersApi } from '@/services/api';

/**
 * Registration captures a display name, but `POST /auth/register` only takes
 * email+password — the profile field is persisted via `PATCH /users/me` once
 * the user can authenticate (same flow as web). Stash it locally until the
 * first successful sign-in.
 */
const STORE_KEY = 'pending-profile';

interface PendingProfile {
  email: string;
  displayName: string;
}

export async function stashPendingProfile(profile: PendingProfile): Promise<void> {
  await writeJson(STORE_KEY, profile);
}

/** Apply after login; only for the matching account, then clear. Fails soft. */
export async function applyPendingProfile(email: string): Promise<void> {
  const stored = await readJson<PendingProfile>(STORE_KEY);
  if (!stored) {
    return;
  }
  if (stored.email.toLowerCase() !== email.toLowerCase()) {
    return;
  }
  try {
    await usersApi.updateMyProfile({ displayName: stored.displayName });
    await removeJson(STORE_KEY);
  } catch (error) {
    console.warn('[auth] applying pending profile failed (will retry next login)', error);
  }
}
