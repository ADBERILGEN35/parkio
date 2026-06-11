export interface UploadMediaResponse {
  mediaId: string;
  status: string;
  contentType: string;
  fileSize: number;
}

export interface MediaAccessUrl {
  mediaId: string;
  accessUrl: string;
  expiresAt: string;
}
