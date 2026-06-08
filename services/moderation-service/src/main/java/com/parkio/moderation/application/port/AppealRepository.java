package com.parkio.moderation.application.port;

import com.parkio.moderation.domain.Appeal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for {@link Appeal}. */
public interface AppealRepository {

    Appeal save(Appeal appeal);

    Optional<Appeal> findById(UUID id);

    boolean existsByCaseIdAndAppealUserId(UUID caseId, UUID appealUserId);

    List<Appeal> findRecent();
}
