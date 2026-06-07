package com.parkio.media.infrastructure.persistence.jpa;

import com.parkio.media.infrastructure.persistence.entity.MediaValidationResultEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MediaValidationResultJpaRepository extends JpaRepository<MediaValidationResultEntity, UUID> {

    List<MediaValidationResultEntity> findByMediaIdOrderByCreatedAtAsc(UUID mediaId);
}
