package com.parkio.media.infrastructure.persistence.jpa;

import com.parkio.media.domain.MediaStatus;
import com.parkio.media.infrastructure.persistence.entity.MediaFileEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MediaFileJpaRepository extends JpaRepository<MediaFileEntity, UUID> {

    boolean existsByOwnerUserIdAndChecksumAndStatusNot(UUID ownerUserId, String checksum, MediaStatus status);

    long countByStatus(MediaStatus status);

    List<MediaFileEntity> findByOwnerUserId(UUID ownerUserId);
}
