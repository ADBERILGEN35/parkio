package com.parkio.moderation.application.port;

import com.parkio.moderation.domain.ModerationCase;
import com.parkio.moderation.domain.ModerationStatus;
import com.parkio.moderation.domain.ModerationTargetType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for {@link ModerationCase}. */
public interface ModerationCaseRepository {

    ModerationCase save(ModerationCase moderationCase);

    Optional<ModerationCase> findById(UUID id);

    /** A still-open (OPEN or IN_REVIEW) case for the target, if any — used to dedupe cases. */
    Optional<ModerationCase> findActiveByTarget(ModerationTargetType targetType, UUID targetId);

    List<ModerationCase> findRecent();

    List<ModerationCase> findByStatus(ModerationStatus status);
}
