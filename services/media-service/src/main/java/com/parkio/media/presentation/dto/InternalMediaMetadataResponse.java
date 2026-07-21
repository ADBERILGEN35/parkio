package com.parkio.media.presentation.dto;

import com.parkio.media.domain.MediaFile;
import java.util.UUID;

/** Internal metadata view for trusted services (vision classification). */
public record InternalMediaMetadataResponse(
        UUID mediaId,
        String contentType,
        long fileSize,
        String status,
        ClaimedRegionDto claimedRegion) {

    public static InternalMediaMetadataResponse from(MediaFile media) {
        ClaimedRegionDto region = null;
        if (media.claimedRegion() != null) {
            var r = media.claimedRegion();
            region = new ClaimedRegionDto(r.x(), r.y(), r.width(), r.height());
        }
        return new InternalMediaMetadataResponse(
                media.id(), media.contentType(), media.fileSize(),
                media.status().name(), region);
    }
}
