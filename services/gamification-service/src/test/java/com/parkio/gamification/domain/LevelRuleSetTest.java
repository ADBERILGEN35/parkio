package com.parkio.gamification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Pure-domain tests for level resolution from point totals. */
class LevelRuleSetTest {

    private final LevelRuleSet rules = new LevelRuleSet(List.of(
            new LevelRule(1, 0, 99L, 300, 3, 20, false, false),
            new LevelRule(2, 100, 299L, 500, 5, 40, false, false),
            new LevelRule(3, 300, 699L, 1000, 10, 75, false, false),
            new LevelRule(4, 700, 1499L, 1500, 15, 150, true, false),
            new LevelRule(5, 1500, null, 2500, 25, 300, true, true)));

    @Test
    void resolvesLevelAcrossThresholds() {
        assertThat(rules.levelFor(0)).isEqualTo(1);
        assertThat(rules.levelFor(99)).isEqualTo(1);
        assertThat(rules.levelFor(100)).isEqualTo(2);
        assertThat(rules.levelFor(299)).isEqualTo(2);
        assertThat(rules.levelFor(300)).isEqualTo(3);
        assertThat(rules.levelFor(699)).isEqualTo(3);
        assertThat(rules.levelFor(700)).isEqualTo(4);
        assertThat(rules.levelFor(1499)).isEqualTo(4);
        assertThat(rules.levelFor(1500)).isEqualTo(5);
        assertThat(rules.levelFor(10_000)).isEqualTo(5);
    }

    @Test
    void ruleForLevelReturnsItsAccessPolicy() {
        LevelRule level4 = rules.ruleForLevel(4);
        assertThat(level4.searchRadiusMeters()).isEqualTo(1500);
        assertThat(level4.resultLimit()).isEqualTo(15);
        assertThat(level4.dailyViewLimit()).isEqualTo(150);
        assertThat(level4.verifiedSpotPriority()).isTrue();
        assertThat(level4.notificationPriority()).isFalse();
    }
}
