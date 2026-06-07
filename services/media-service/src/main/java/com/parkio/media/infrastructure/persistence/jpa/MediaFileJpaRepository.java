package com.parkio.media.infrastructure.persistence.jpa;

import com.parkio.media.infrastructure.persistence.entity.MediaFileEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MediaFileJpaRepository extends JpaRepository<MediaFileEntity, UUID> {

    boolean existsByChecksum(String checksum);
}
