import * as SecureStore from 'expo-secure-store';
import type { SpotCreationDraft } from '../state/spotCreationDraftStore';
import { getFileSize } from '@/features/media/lib/fileSystem';

const STORAGE_KEY = 'parkio.spotCreationDraft';

function sanitize(raw: unknown): SpotCreationDraft | null {
  if (!raw || typeof raw !== 'object') return null;
  const d = raw as Partial<SpotCreationDraft>;
  if (
    typeof d.previewUri !== 'string' ||
    !d.media ||
    typeof d.media.mediaId !== 'string' ||
    typeof d.manualLocationEdited !== 'boolean' ||
    typeof d.vehicleType !== 'string' ||
    typeof d.parkingContext !== 'string' ||
    typeof d.note !== 'string'
  ) {
    return null;
  }
  if (d.location != null && (typeof d.location.lat !== 'number' || typeof d.location.lng !== 'number')) {
    return null;
  }
  if (getFileSize(d.previewUri) <= 0) return null;
  return d as SpotCreationDraft;
}

/** Restore a spot-creation draft after process death. Never throws. */
export async function loadPersistedSpotCreationDraft(): Promise<SpotCreationDraft | null> {
  try {
    const json = await SecureStore.getItemAsync(STORAGE_KEY);
    if (!json) return null;
    return sanitize(JSON.parse(json));
  } catch {
    return null;
  }
}

/** Persist the in-progress draft. Never throws. */
export async function persistSpotCreationDraft(draft: SpotCreationDraft): Promise<void> {
  try {
    await SecureStore.setItemAsync(STORAGE_KEY, JSON.stringify(draft));
  } catch {
    // Best-effort — losing a draft is preferable to blocking spot creation.
  }
}

/** Remove persisted draft metadata. Never throws. */
export async function clearPersistedSpotCreationDraft(): Promise<void> {
  try {
    await SecureStore.deleteItemAsync(STORAGE_KEY);
  } catch {
    /* ignore */
  }
}