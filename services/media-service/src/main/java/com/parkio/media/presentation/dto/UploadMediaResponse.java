package com.parkio.media.presentation.dto;

import com.parkio.media.application.result.MediaUploadResult;
import java.util.UUID;

/** Response for a successful upload. */
public record UploadMediaResponse(UUID mediaId, String status, String contentType, long fileSize) {

    public static UploadMediaResponse from(MediaUploadResult result) {
        return new UploadMediaResponse(result.mediaId(), result.status().name(),
                result.contentType(), result.fileSize());
    }
}
