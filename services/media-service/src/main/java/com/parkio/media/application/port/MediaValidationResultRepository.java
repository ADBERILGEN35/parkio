package com.parkio.media.application.port;

import com.parkio.media.domain.MediaValidationResult;
import java.util.List;
import java.util.UUID;

/** Persistence port for {@link MediaValidationResult} (append-only). */
public interface MediaValidationResultRepository {

    MediaValidationResult save(MediaValidationResult result);

    List<MediaValidationResult> findByMediaId(UUID mediaId);
}
