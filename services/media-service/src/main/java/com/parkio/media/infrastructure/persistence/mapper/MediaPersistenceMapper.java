package com.parkio.media.infrastructure.persistence.mapper;

import com.parkio.media.domain.ClaimedRegion;
import com.parkio.media.domain.MediaFile;
import com.parkio.media.domain.MediaValidationResult;
import com.parkio.media.infrastructure.persistence.entity.MediaFileEntity;
import com.parkio.media.infrastructure.persistence.entity.MediaValidationResultEntity;

/**
 * Translates between domain models and JPA entities, keeping the adapters thin and
 * the domain persistence-agnostic.
 */
public final class MediaPersistenceMapper {

    private MediaPersistenceMapper() {
    }

    public static MediaFile toDomain(MediaFileEntity e) {
        ClaimedRegion region = null;
        if (e.getClaimedRegionX() != null) {
            region = ClaimedRegion.of(
                    e.getClaimedRegionX(),
                    e.getClaimedRegionY(),
                    e.getClaimedRegionWidth(),
                    e.getClaimedRegionHeight());
        }
        return new MediaFile(e.getId(), e.getOwnerUserId(), e.getBucketName(), e.getObjectKey(),
                e.getContentType(), e.getFileSize(), e.getChecksum(),
                e.getPerceptualHash(), region, e.getStatus(), e.getCreatedAt(), e.getUpdatedAt(),
                e.getDeletedAt(), e.getVersion());
    }

    public static MediaFileEntity toEntity(MediaFile m) {
        ClaimedRegion region = m.claimedRegion();
        return new MediaFileEntity(m.id(), m.ownerUserId(), m.bucketName(), m.objectKey(),
                m.contentType(), m.fileSize(), m.checksum(), m.perceptualHash(),
                region == null ? null : region.x(),
                region == null ? null : region.y(),
                region == null ? null : region.width(),
                region == null ? null : region.height(),
                m.status(), m.createdAt(), m.updatedAt(), m.deletedAt(), m.version());
    }

    public static MediaValidationResult toDomain(MediaValidationResultEntity e) {
        return new MediaValidationResult(e.getId(), e.getMediaId(), e.getValidationType(),
                e.getResult(), e.getMessage(), e.getCreatedAt());
    }

    public static MediaValidationResultEntity toEntity(MediaValidationResult r) {
        return new MediaValidationResultEntity(r.id(), r.mediaId(), r.validationType(),
                r.result(), r.message(), r.createdAt());
    }
}
