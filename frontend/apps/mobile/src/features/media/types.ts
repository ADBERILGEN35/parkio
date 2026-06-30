import type { AllowedContentType } from './constants';

/** A locally-acquired image, straight from the camera or the gallery picker. */
export interface LocalAsset {
  uri: string;
  width: number;
  height: number;
  /** Bytes, when the source reports it (gallery picker usually does; camera may not). */
  fileSize?: number;
  /** Mime type the source reports, if any. */
  mimeType?: string;
  /** True when the uri points to a cache file created by this flow. */
  temporary?: boolean;
}

/** An image that has been resized/re-encoded and is ready to upload. */
export interface PreparedImage {
  uri: string;
  width: number;
  height: number;
  fileSize: number;
  contentType: AllowedContentType;
  /** Filename sent in the multipart part. */
  name: string;
}

/** Where the user wants to get the image from. */
export type MediaSource = 'camera' | 'library';

/** The upload state machine surfaced by {@link useMediaUpload}. */
export type UploadPhase = 'idle' | 'preparing' | 'uploading' | 'success' | 'error' | 'cancelled';
