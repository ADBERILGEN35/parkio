package com.parkio.gamification.domain;

/**
 * A configurable reward: a stable {@code ruleKey} (event + recipient role) maps to a
 * positive point value and its {@link PointSourceType}. Seeded in the database so
 * values are data, not code.
 */
public record RewardRule(String ruleKey, PointSourceType sourceType, int points, String description) {
}
