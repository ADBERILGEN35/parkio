import { File } from 'expo-file-system';

/**
 * Thin, synchronous seam over `expo-file-system`'s object-oriented `File` API
 * (SDK 56). Centralised here so the rest of the feature never touches the native
 * module directly, and so tests mock a single module.
 *
 * Cleanup is intentionally best-effort: capture/preparation write to the cache
 * directory, which the OS may reclaim anyway, so a failed delete must never
 * surface as an error to the user.
 */

/** Size of a local file in bytes; 0 when missing/unreadable. */
export function getFileSize(uri: string): number {
  try {
    return new File(uri).size;
  } catch {
    return 0;
  }
}

/** Delete a single temp file if it exists. Never throws. */
export function deleteTempFile(uri: string | undefined | null): void {
  if (!uri) return;
  try {
    const file = new File(uri);
    if (file.exists) {
      file.delete();
    }
  } catch {
    // Best-effort: cache files are reclaimable; swallow cleanup failures.
  }
}

/** Delete every provided temp file. Never throws. */
export function deleteTempFiles(uris: Iterable<string | undefined | null>): void {
  for (const uri of uris) {
    deleteTempFile(uri);
  }
}
