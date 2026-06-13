import { describe, expect, it } from 'vitest';
import { MEDIA_MAX_FILE_SIZE_BYTES, mediaUploadSchema } from './media';

function makeFile(type: string, sizeBytes = 16) {
  return new File([new Uint8Array(sizeBytes)], 'photo', { type });
}

describe('mediaUploadSchema', () => {
  it.each(['image/jpeg', 'image/png', 'image/webp'])('accepts %s', (type) => {
    expect(mediaUploadSchema.safeParse({ file: makeFile(type) }).success).toBe(true);
  });

  it.each(['image/gif', 'application/pdf', 'text/plain'])('rejects %s', (type) => {
    expect(mediaUploadSchema.safeParse({ file: makeFile(type) }).success).toBe(false);
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
