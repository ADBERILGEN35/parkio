package com.parkio.media.infrastructure.persistence;

import com.parkio.media.application.port.MediaFileRepository;
import com.parkio.media.domain.MediaFile;
import com.parkio.media.infrastructure.persistence.jpa.MediaFileJpaRepository;
import com.parkio.media.infrastructure.persistence.mapper.MediaPersistenceMapper;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Adapts the {@link MediaFileRepository} port to Spring Data JPA. */
@Component
public class MediaFileRepositoryAdapter implements MediaFileRepository {

    private final MediaFileJpaRepository jpa;

    public MediaFileRepositoryAdapter(MediaFileJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public MediaFile save(MediaFile media) {
        return MediaPersistenceMapper.toDomain(jpa.save(MediaPersistenceMapper.toEntity(media)));
    }

    @Override
    public Optional<MediaFile> findById(UUID id) {
        return jpa.findById(id).map(MediaPersistenceMapper::toDomain);
    }

    @Override
    public boolean existsByChecksum(String checksum) {
        return jpa.existsByChecksum(checksum);
    }
}
