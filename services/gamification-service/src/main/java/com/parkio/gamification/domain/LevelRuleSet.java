package com.parkio.gamification.domain;

import java.util.Comparator;
import java.util.List;

/**
 * The ordered set of {@link LevelRule}s, with the logic to resolve a point total to
 * a level and to look up a level's rule. A pure-domain value built from the seeded
 * rules; no framework dependencies.
 */
public final class LevelRuleSet {

    private final List<LevelRule> rules;

    public LevelRuleSet(List<LevelRule> rules) {
        if (rules == null || rules.isEmpty()) {
            throw new IllegalArgumentException("level rules must not be empty");
        }
        this.rules = rules.stream().sorted(Comparator.comparingInt(LevelRule::level)).toList();
    }

    /** The rule whose range contains {@code points}; falls back to the highest level. */
    public LevelRule ruleFor(long points) {
        return rules.stream()
                .filter(rule -> rule.matches(points))
                .findFirst()
                .orElse(rules.get(rules.size() - 1));
    }

    /** The level number for the given point total. */
    public int levelFor(long points) {
        return ruleFor(points).level();
    }

    public LevelRule ruleForLevel(int level) {
        return rules.stream()
                .filter(rule -> rule.level() == level)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No level rule for level " + level));
    }

    public List<LevelRule> rules() {
        return rules;
    }
}
