package com.parkio.gamification.domain;

/**
 * A configurable trust-score adjustment: a stable {@code ruleKey} maps to a signed
 * delta (positive for contributions, negative for penalties). Seeded in the database
 * so values are data, not code (ai-context/02).
 */
public record TrustRule(String ruleKey, int delta, String description) {
}
