package com.parkio.gamification.presentation.dto;

import com.parkio.gamification.domain.LevelRule;

/** A level definition (for the public levels listing). */
public record LevelRuleResponse(
        int level,
        long minPoints,
        Long maxPoints,
        int searchRadiusMeters,
        int resultLimit,
        int dailyViewLimit,
        boolean verifiedSpotPriority,
        boolean notificationPriority) {

    public static LevelRuleResponse from(LevelRule r) {
        return new LevelRuleResponse(r.level(), r.minPoints(), r.maxPoints(), r.searchRadiusMeters(),
                r.resultLimit(), r.dailyViewLimit(), r.verifiedSpotPriority(), r.notificationPriority());
    }
}
