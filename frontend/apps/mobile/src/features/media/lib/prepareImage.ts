import { ImageManipulator, SaveFormat } from 'expo-image-manipulator';
import {
  MAX_FILE_SIZE_BYTES,
  PREPARE_INITIAL_QUALITY,
  PREPARE_MAX_EDGE,
  PREPARE_MIN_QUALITY,
  PREPARE_QUALITY_STEP,
  PREPARED_CONTENT_TYPE,
  PREPARED_FILE_EXTENSION,
} from '../constants';
import type { LocalAsset, PreparedImage } from '../types';
import { deleteTempFile, getFileSize } from './fileSystem';

/**
 * Resize target for the longest edge. Returns an empty object when the image is
 * already within {@link PREPARE_MAX_EDGE} so we skip a pointless upscale/resize.
 * Only one dimension is constrained; the manipulator preserves aspect ratio.
 */
function resizeTarget(asset: LocalAsset): { width?: number; height?: number } | null {
  const longest = Math.max(asset.width, asset.height);
  if (longest <= 0 || longest <= PREPARE_MAX_EDGE) {
    return null;
  }
  return asset.width >= asset.height ? { width: PREPARE_MAX_EDGE } : { height: PREPARE_MAX_EDGE };
}

/**
 * Prepares a captured/picked image for upload:
 *  - resizes the longest edge down to {@link PREPARE_MAX_EDGE} (keeps it under
 *    every backend pixel ceiling and shrinks payloads dramatically),
 *  - re-encodes to JPEG, which **strips EXIF metadata** (location, device, etc.)
 *    while baking in the correct display orientation,
 *  - steps quality down until the encoded file is under {@link MAX_FILE_SIZE_BYTES}.
 *
 * The original asset uri is the caller's to clean up; every intermediate temp
 * file this function writes and then discards is deleted here.
 */
export async function prepareImage(asset: LocalAsset): Promise<PreparedImage> {
  const context = ImageManipulator.manipulate(asset.uri);
  const target = resizeTarget(asset);
  if (target) {
    context.resize(target);
  }
  // Render once; re-save at decreasing quality reuses the same resized image.
  const ref = await context.renderAsync();

  let quality = PREPARE_INITIAL_QUALITY;
  let result = await ref.saveAsync({ compress: quality, format: SaveFormat.JPEG });
  let fileSize = getFileSize(result.uri);

  while (fileSize > MAX_FILE_SIZE_BYTES && quality > PREPARE_MIN_QUALITY) {
    quality = Math.max(PREPARE_MIN_QUALITY, quality - PREPARE_QUALITY_STEP);
    // Discard the oversized encode before writing the next, lower-quality one.
    deleteTempFile(result.uri);
    result = await ref.saveAsync({ compress: quality, format: SaveFormat.JPEG });
    fileSize = getFileSize(result.uri);
  }

  return {
    uri: result.uri,
    width: result.width,
    height: result.height,
    fileSize,
    contentType: PREPARED_CONTENT_TYPE,
    name: `parkio-spot-${Date.now()}.${PREPARED_FILE_EXTENSION}`,
  };
}
