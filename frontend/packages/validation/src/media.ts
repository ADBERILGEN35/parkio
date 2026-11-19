import { z } from 'zod';

// Mirrors media-service parkio.media settings (allowed-content-types / max-file-size).
// No WebP: media-service cannot decode it, so it is refused here before any upload.
export const ALLOWED_IMAGE_TYPES = ['image/jpeg', 'image/png'] as const;
export const MEDIA_MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024; // 10MB

export function isAllowedImageType(type: string): boolean {
  return ALLOWED_IMAGE_TYPES.includes(type as (typeof ALLOWED_IMAGE_TYPES)[number]);
}

export const mediaUploadSchema = z.object({
  file: z
    .instanceof(File)
    .refine((f) => f.size > 0, 'Choose a photo to upload')
    .refine((f) => isAllowedImageType(f.type), {
      message: 'Only JPEG and PNG images are allowed',
    })
    .refine((f) => f.size <= MEDIA_MAX_FILE_SIZE_BYTES, 'Photo must be at most 10MB'),
});

export type MediaUploadFormValues = z.infer<typeof mediaUploadSchema>;
