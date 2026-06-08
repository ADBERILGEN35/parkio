package com.parkio.moderation.application.port;

import com.parkio.moderation.domain.ModerationDecision;

/** Persistence port for {@link ModerationDecision} (append-only audit). */
public interface ModerationDecisionRepository {

    ModerationDecision save(ModerationDecision decision);
}
