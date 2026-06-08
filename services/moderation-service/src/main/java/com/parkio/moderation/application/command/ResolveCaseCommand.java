package com.parkio.moderation.application.command;

import com.parkio.moderation.domain.ModerationAction;
import java.util.UUID;

/** A moderator's request to resolve a case. */
public record ResolveCaseCommand(UUID caseId, UUID moderatorId, ModerationAction action, String note) {
}
