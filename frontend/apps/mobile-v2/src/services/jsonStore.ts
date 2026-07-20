import { Directory, File, Paths } from 'expo-file-system';

/**
 * Tiny JSON persistence on the app's document directory — used for
 * non-sensitive state that must survive cold starts (locale choice, onboarding
 * progress, share-flow drafts, recent searches). Secrets NEVER go here — they
 * belong in `secureStore`.
 *
 * Each key maps to one `<key>.json` file. Reads/writes fail soft: a corrupt or
 * missing file reads as `null`, a failed write is swallowed after a console
 * warning (persistence is best-effort; in-memory state stays authoritative
 * while the app runs).
 */
const DIR_NAME = 'parkio-store';

function storeDir(): Directory {
  return new Directory(Paths.document, DIR_NAME);
}

function fileFor(key: string): File {
  return new File(Paths.document, DIR_NAME, `${key}.json`);
}

export async function readJson<T>(key: string): Promise<T | null> {
  try {
    const file = fileFor(key);
    if (!file.exists) {
      return null;
    }
    return JSON.parse(file.textSync()) as T;
  } catch {
    return null;
  }
}

export async function writeJson(key: string, value: unknown): Promise<void> {
  try {
    const dir = storeDir();
    if (!dir.exists) {
      dir.create({ intermediates: true });
    }
    fileFor(key).write(JSON.stringify(value));
  } catch (error) {
    console.warn(`[jsonStore] failed to persist "${key}"`, error);
  }
}

export async function removeJson(key: string): Promise<void> {
  try {
    const file = fileFor(key);
    if (file.exists) {
      file.delete();
    }
  } catch {
    // Best-effort.
  }
}
