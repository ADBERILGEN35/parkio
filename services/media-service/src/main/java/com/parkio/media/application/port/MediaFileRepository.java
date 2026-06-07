package com.parkio.media.application.port;

import com.parkio.media.domain.MediaFile;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for {@link MediaFile}. */
public interface MediaFileRepository {

    MediaFile save(MediaFile media);

    Optional<MediaFile> findById(UUID id);

    boolean existsByChecksum(String checksum);
}
