package com.parkio.media.application.port;

import com.parkio.media.domain.MediaFile;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for {@link MediaFile}. */
public interface MediaFileRepository {

    MediaFile save(MediaFile media);

    Optional<MediaFile> findById(UUID id);

    /**
     * Whether {@code ownerUserId} already has a file with this normalized checksum that is not
     * deleted. Other owners' files and deleted files never count (CL-F38c).
     */
    boolean existsLiveDuplicate(UUID ownerUserId, String checksum);
}
