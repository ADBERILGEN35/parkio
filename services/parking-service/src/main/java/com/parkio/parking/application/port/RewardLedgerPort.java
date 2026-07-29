package com.parkio.parking.application.port;

import com.parkio.parking.reward.PendingRewardIntent;
import com.parkio.parking.reward.RewardSubject;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Append-only pending reward history. */
public interface RewardLedgerPort {

    void append(PendingRewardIntent intent);

    Optional<PendingRewardIntent> findByEvaluationId(UUID evaluationId);

    List<PendingRewardIntent> findBySubject(RewardSubject subject);

    Optional<PendingRewardIntent> findLatestForContribution(UUID sourceContributionId);
}
