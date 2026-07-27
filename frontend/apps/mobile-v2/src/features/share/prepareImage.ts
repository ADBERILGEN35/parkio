import { ImageManipulator, SaveFormat } from 'expo-image-manipulator';
import { Directory, File, Paths } from 'expo-file-system';
import type { DraftPhoto } from './state/shareDraftStore';

/** Longest edge after preparation — plenty for AI validation + display. */
const MAX_WIDTH = 1600;
const JPEG_QUALITY = 0.75;

const DRAFT_DIR = 'parkio-store';
const LEGACY_DRAFT_PHOTO = 'draft-photo.jpg';
const DRAFT_PHOTO_PREFIX = 'draft-photo-';

let draftCopySeq = 0;

/**
 * Prepare a captured/picked image for upload: downscale to ≤1600px, re-encode
 * as JPEG (which also drops EXIF client-side — the server strips it again,
 * defense in depth), then copy into the document dir so a persisted draft
 * survives cache eviction and cold starts.
 *
 * Each prepared image gets a unique durable filename so React Native Image does
 * not reuse a cached bitmap when the user replaces the photo.
 */
export async function prepareImage(input: {
  uri: string;
  width?: number;
  height?: number;
}): Promise<Omit<DraftPhoto, 'revision'>> {
  const context = ImageManipulator.manipulate(input.uri);
  if (!input.width || input.width > MAX_WIDTH) {
    context.resize({ width: MAX_WIDTH });
  }
  const rendered = await context.renderAsync();
  try {
    const saved = await rendered.saveAsync({ compress: JPEG_QUALITY, format: SaveFormat.JPEG });
    const durable = persistDraftCopy(saved.uri);
    return { uri: durable ?? saved.uri, width: saved.width, height: saved.height };
  } finally {
    rendered.release();
  }
}

function nextDraftPhotoFileName(): string {
  draftCopySeq += 1;
  return `${DRAFT_PHOTO_PREFIX}${draftCopySeq}-${Date.now()}.jpg`;
}

/** Copy the prepared JPEG into the document dir; returns the durable uri. */
function persistDraftCopy(sourceUri: string): string | null {
  try {
    const dir = new Directory(Paths.document, DRAFT_DIR);
    if (!dir.exists) {
      dir.create({ intermediates: true });
    }
    const destination = new File(Paths.document, DRAFT_DIR, nextDraftPhotoFileName());
    new File(sourceUri).copy(destination);
    return destination.uri;
  } catch (error) {
    console.warn('[share] persisting draft photo failed (using cache uri)', error);
    return null;
  }
}

/** Remove one app-owned draft photo file when safe. */
export function deleteAppOwnedDraftPhoto(uri: string | null | undefined): void {
  if (!isAppOwnedDraftPhotoUri(uri)) {
    return;
  }
  try {
    const file = new File(uri!);
    if (file.exists) {
      file.delete();
    }
  } catch (error) {
    console.warn('[share] deleteAppOwnedDraftPhoto failed', error);
  }
}

/** Remove all persisted draft photos (draft discarded or published). */
export function deleteDraftPhoto(): void {
  try {
    const dir = new Directory(Paths.document, DRAFT_DIR);
    if (!dir.exists) {
      return;
    }
    for (const entry of dir.list()) {
      const name = entry.uri.split('/').pop() ?? '';
      if (
        name === LEGACY_DRAFT_PHOTO ||
        (name.startsWith(DRAFT_PHOTO_PREFIX) && name.endsWith('.jpg'))
      ) {
        entry.delete();
      }
    }
  } catch (error) {
    console.warn('[share] deleteDraftPhoto failed', error);
  }
}

/**
 * True only for the app-owned draft copy under the document directory.
 * User gallery/camera cache URIs must never be deleted by cancel/reset.
 */
export function isAppOwnedDraftPhotoUri(uri: string | null | undefined): boolean {
  if (!uri) {
    return false;
  }
  const name = uri.split('/').pop() ?? '';
  if (name === LEGACY_DRAFT_PHOTO) {
    return uri.includes(`/${DRAFT_DIR}/`);
  }
  return name.startsWith(DRAFT_PHOTO_PREFIX) && name.endsWith('.jpg') && uri.includes(`/${DRAFT_DIR}/`);
}

/** True when a draft photo uri still resolves to a readable local file. */
export function draftPhotoExists(uri: string | null | undefined): boolean {
  if (!uri) {
    return false;
  }
  try {
    return new File(uri).exists;
  } catch (error) {
    console.warn('[share] draftPhotoExists check failed', error);
    return false;
  }
}

/** Test-only reset for deterministic draft filenames. */
export function resetDraftPhotoCopySeq(): void {
  draftCopySeq = 0;
}
