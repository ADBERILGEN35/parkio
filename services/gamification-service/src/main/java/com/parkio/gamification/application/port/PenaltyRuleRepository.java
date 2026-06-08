package com.parkio.gamification.application.port;

import com.parkio.gamification.domain.PenaltyRule;
import java.util.Optional;

/** Read port for the seeded {@link PenaltyRule}s. */
public interface PenaltyRuleRepository {

    Optional<PenaltyRule> findByRuleKey(String ruleKey);
}
