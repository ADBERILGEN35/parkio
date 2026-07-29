package com.parkio.parking.decision.evidence;

/**
 * Origin of a normalized {@link EvidenceItem}. Sources identify providers, not
 * score algorithms. Vocabulary only — a source may be unused until a later WP.
 */
public enum EvidenceSource {

    AI_VALIDATION_SERVICE,
    PARKING_DOMAIN,
    GAMIFICATION_SERVICE,
    MODERATION_SERVICE,
    CLIENT_DEVICE,
    USER_OUTCOME,
    SYSTEM
}