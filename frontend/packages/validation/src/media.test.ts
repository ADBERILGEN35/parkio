import { describe, expect, it } from 'vitest';
import { ALLOWED_IMAGE_TYPES, MEDIA_MAX_FILE_SIZE_BYTES, mediaUploadSchema } from './media';

function makeFile(type: string, sizeBytes = 16) {
  return new File([new Uint8Array(sizeBytes)], 'photo', { type });
}

describe('mediaUploadSchema', () => {
  it.each(['image/jpeg', 'image/png'])('accepts %s', (type) => {
    expect(mediaUploadSchema.safeParse({ file: makeFile(type) }).success).toBe(true);
  });

  it.each(['image/webp', 'image/gif', 'application/pdf', 'text/plain'])('rejects %s', (type) => {
    expect(mediaUploadSchema.safeParse({ file: makeFile(type) }).success).toBe(false);
  });

  it('refuses WebP with the unsupported-type message (media-service cannot decode it)', () => {
    const result = mediaUploadSchema.safeParse({ file: makeFile('image/webp') });
    expect(result.success).toBe(false);
    expect(result.error?.issues[0]?.message).toBe('Only JPEG and PNG images are allowed');
    expect(ALLOWED_IMAGE_TYPES).toEqual(['image/jpeg', 'image/png']);
  });

  it('rejects empty files', () => {
    expect(mediaUploadSchema.safeParse({ file: makeFile('image/jpeg', 0) }).success).toBe(false);
  });

  it('accepts files exactly at the 10MB limit', () => {
    const file = makeFile('image/jpeg', MEDIA_MAX_FILE_SIZE_BYTES);
    expect(mediaUploadSchema.safeParse({ file }).success).toBe(true);
  });

  it('rejects files over 10MB', () => {
    const file = makeFile('image/jpeg', MEDIA_MAX_FILE_SIZE_BYTES + 1);
    expect(mediaUploadSchema.safeParse({ file }).success).toBe(false);
  });

  it('rejects non-File values', () => {
    expect(mediaUploadSchema.safeParse({ file: 'photo.jpg' }).success).toBe(false);
  });
});
