package com.parkio.moderation.application.command;

import com.parkio.moderation.domain.ModerationAction;
import java.util.UUID;

/**
 * A moderator's request to resolve a case. {@code callerIsAdmin} carries the
 * caller's privilege so the application service can re-enforce that admin-only
 * actions ({@link ModerationAction#requiresAdmin()}) are rejected without the
 * ADMIN role, independently of the presentation-layer check (defense in depth).
 */
public record ResolveCaseCommand(UUID caseId, UUID moderatorId, ModerationAction action, String note,
                                 boolean callerIsAdmin) {
}
