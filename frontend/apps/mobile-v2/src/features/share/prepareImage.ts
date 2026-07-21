import { ImageManipulator, SaveFormat } from 'expo-image-manipulator';
import { Directory, File, Paths } from 'expo-file-system';
import type { DraftPhoto } from './state/shareDraftStore';

/** Longest edge after preparation — plenty for AI validation + display. */
const MAX_WIDTH = 1600;
const JPEG_QUALITY = 0.75;

const DRAFT_DIR = 'parkio-store';
const DRAFT_PHOTO = 'draft-photo.jpg';

/**
 * Prepare a captured/picked image for upload: downscale to ≤1600px, re-encode
 * as JPEG (which also drops EXIF client-side — the server strips it again,
 * defense in depth), then copy into the document dir so a persisted draft
 * survives cache eviction and cold starts.
 */
export async function prepareImage(input: {
  uri: string;
  width?: number;
  height?: number;
}): Promise<DraftPhoto> {
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

/** Copy the prepared JPEG into the document dir; returns the durable uri. */
function persistDraftCopy(sourceUri: string): string | null {
  try {
    const dir = new Directory(Paths.document, DRAFT_DIR);
    if (!dir.exists) {
      dir.create({ intermediates: true });
    }
    const destination = new File(Paths.document, DRAFT_DIR, DRAFT_PHOTO);
    if (destination.exists) {
      destination.delete();
    }
    new File(sourceUri).copy(destination);
    return destination.uri;
  } catch (error) {
    console.warn('[share] persisting draft photo failed (using cache uri)', error);
    return null;
  }
}

/** Remove the persisted draft photo (draft discarded or published). */
export function deleteDraftPhoto(): void {
  try {
    const file = new File(Paths.document, DRAFT_DIR, DRAFT_PHOTO);
    if (file.exists) {
      file.delete();
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
  try {
    const owned = new File(Paths.document, DRAFT_DIR, DRAFT_PHOTO).uri;
    return uri === owned || uri.endsWith(`/${DRAFT_DIR}/${DRAFT_PHOTO}`);
  } catch {
    return uri.includes(`${DRAFT_DIR}/${DRAFT_PHOTO}`);
  }
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
