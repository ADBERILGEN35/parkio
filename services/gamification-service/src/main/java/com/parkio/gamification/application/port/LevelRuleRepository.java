package com.parkio.gamification.application.port;

import com.parkio.gamification.domain.LevelRule;
import java.util.List;

/** Read port for the seeded {@link LevelRule}s. */
public interface LevelRuleRepository {

    List<LevelRule> findAll();
}
