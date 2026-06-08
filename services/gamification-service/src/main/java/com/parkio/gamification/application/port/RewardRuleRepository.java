package com.parkio.gamification.application.port;

import com.parkio.gamification.domain.RewardRule;
import java.util.Optional;

/** Read port for the seeded {@link RewardRule}s. */
public interface RewardRuleRepository {

    Optional<RewardRule> findByRuleKey(String ruleKey);
}
