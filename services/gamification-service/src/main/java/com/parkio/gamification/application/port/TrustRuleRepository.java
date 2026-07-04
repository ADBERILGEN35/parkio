package com.parkio.gamification.application.port;

import com.parkio.gamification.domain.TrustRule;
import java.util.Optional;

/** Read port for the seeded {@link TrustRule}s. */
public interface TrustRuleRepository {

    Optional<TrustRule> findByRuleKey(String ruleKey);
}
