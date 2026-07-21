package com.parkio.media.presentation.dto;

import com.parkio.media.application.result.MediaUploadResult;
import java.util.UUID;

public record UploadMediaResponse(
        UUID mediaId,
        String status,
        String contentType,
        long fileSize,
        ClaimedRegionDto claimedRegion) {

    public static UploadMediaResponse from(MediaUploadResult result) {
        ClaimedRegionDto region = null;
        if (result.claimedRegion() != null) {
            var r = result.claimedRegion();
            region = new ClaimedRegionDto(r.x(), r.y(), r.width(), r.height());
        }
        return new UploadMediaResponse(result.mediaId(), result.status().name(),
                result.contentType(), result.fileSize(), region);
    }
}
