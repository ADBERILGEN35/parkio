import { ALLOWED_CONTENT_TYPES, MAX_FILE_SIZE_BYTES, MAX_IMAGE_PIXELS } from '../constants';
import type { LocalAsset } from '../types';

export type MediaRejectionReason = 'unsupported-type' | 'too-large' | 'too-many-pixels' | 'empty';

export interface MediaValidation {
  ok: boolean;
  reason?: MediaRejectionReason;
  message?: string;
}

const REASON_COPY: Record<MediaRejectionReason, string> = {
  'unsupported-type': 'That image format isn’t supported. Use a JPEG, PNG, or WebP photo.',
  'too-large': 'That photo is too large to upload. Try taking a new one.',
  'too-many-pixels': 'That photo’s resolution is too high to upload.',
  empty: 'That file looks empty. Try capturing the photo again.',
};

function reject(reason: MediaRejectionReason): MediaValidation {
  return { ok: false, reason, message: REASON_COPY[reason] };
}

/**
 * Source formats the backend doesn't accept but the device can decode —
 * preparation re-encodes them to JPEG, so rejecting them here would dead-end
 * perfectly usable photos (iOS gallery images are HEIC by default).
 */
const RECODED_SOURCE_TYPES: string[] = ['image/heic', 'image/heif'];

/**
 * Pre-flight check on a freshly-acquired asset, mirroring the backend's
 * constraints. Most assets pass straight through; preparation
 * ({@link prepareImage}) then guarantees the uploaded bytes are within limits.
 * This guards the rare case where preparation can't help (e.g. an absurd
 * pixel count) so we fail fast with a human message instead of a 4xx.
 *
 * Type is only rejected when the source explicitly reports an unsupported mime —
 * a missing mime is tolerated because we always re-encode to JPEG before upload.
 * HEIC/HEIF sources (typical iOS gallery photos) are likewise accepted: the
 * backend never sees them, since preparation re-encodes everything to JPEG.
 */
export function validateLocalAsset(asset: LocalAsset): MediaValidation {
  if (asset.fileSize != null && asset.fileSize <= 0) {
    return reject('empty');
  }
  const mime = asset.mimeType?.toLowerCase();
  if (
    mime &&
    !ALLOWED_CONTENT_TYPES.includes(mime as (typeof ALLOWED_CONTENT_TYPES)[number]) &&
    !RECODED_SOURCE_TYPES.includes(mime)
  ) {
    return reject('unsupported-type');
  }
  if (asset.width > 0 && asset.height > 0 && asset.width * asset.height > MAX_IMAGE_PIXELS) {
    return reject('too-many-pixels');
  }
  return { ok: true };
}

/** Final guard on prepared bytes — preparation should always satisfy this. */
export function isWithinUploadSize(fileSize: number): boolean {
  return fileSize > 0 && fileSize <= MAX_FILE_SIZE_BYTES;
}
