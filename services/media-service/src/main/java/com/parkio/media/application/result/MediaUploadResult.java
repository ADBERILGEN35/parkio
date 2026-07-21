package com.parkio.media.application.result;

import com.parkio.media.domain.ClaimedRegion;
import com.parkio.media.domain.MediaFile;
import com.parkio.media.domain.MediaStatus;
import java.util.UUID;

/** Outcome returned to the caller after a successful upload. */
public record MediaUploadResult(
        UUID mediaId,
        MediaStatus status,
        String contentType,
        long fileSize,
        ClaimedRegion claimedRegion) {

    public static MediaUploadResult from(MediaFile media) {
        return new MediaUploadResult(media.id(), media.status(), media.contentType(),
                media.fileSize(), media.claimedRegion());
    }
}
