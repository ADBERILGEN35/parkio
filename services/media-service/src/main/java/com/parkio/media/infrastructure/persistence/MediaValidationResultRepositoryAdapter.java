package com.parkio.media.infrastructure.persistence;

import com.parkio.media.application.port.MediaValidationResultRepository;
import com.parkio.media.domain.MediaValidationResult;
import com.parkio.media.infrastructure.persistence.jpa.MediaValidationResultJpaRepository;
import com.parkio.media.infrastructure.persistence.mapper.MediaPersistenceMapper;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Adapts the {@link MediaValidationResultRepository} port to Spring Data JPA. */
@Component
public class MediaValidationResultRepositoryAdapter implements MediaValidationResultRepository {

    private final MediaValidationResultJpaRepository jpa;

    public MediaValidationResultRepositoryAdapter(MediaValidationResultJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public MediaValidationResult save(MediaValidationResult result) {
        return MediaPersistenceMapper.toDomain(jpa.save(MediaPersistenceMapper.toEntity(result)));
    }

    @Override
    public List<MediaValidationResult> findByMediaId(UUID mediaId) {
        return jpa.findByMediaIdOrderByCreatedAtAsc(mediaId).stream()
                .map(MediaPersistenceMapper::toDomain)
                .toList();
    }
}
