package com.parkio.gamification.domain;

/**
 * A configurable penalty: a stable {@code ruleKey} maps to a positive point
 * magnitude (applied as a deduction) and its {@link PointSourceType}. Seeded in the
 * database so values are data, not code.
 */
public record PenaltyRule(String ruleKey, PointSourceType sourceType, int points, String description) {
}
