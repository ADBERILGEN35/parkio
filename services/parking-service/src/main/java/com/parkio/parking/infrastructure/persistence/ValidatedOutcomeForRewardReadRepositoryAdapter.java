package com.parkio.parking.infrastructure.persistence;

import com.parkio.parking.application.port.ValidatedOutcomeForRewardReadPort;
import com.parkio.parking.application.reward.ValidatedOutcomeForReward;
import com.parkio.parking.infrastructure.persistence.jpa.OutcomeHistoryJpaRepository;
import com.parkio.parking.infrastructure.persistence.trust.OutcomeHistoryRecordReader;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ValidatedOutcomeForRewardReadRepositoryAdapter implements ValidatedOutcomeForRewardReadPort {

    private final OutcomeHistoryJpaRepository outcomes;
    private final OutcomeHistoryRecordReader historyReader;

    public ValidatedOutcomeForRewardReadRepositoryAdapter(
            OutcomeHistoryJpaRepository outcomes,
            OutcomeHistoryRecordReader historyReader) {
        this.outcomes = outcomes;
        this.historyReader = historyReader;
    }

    @Override
    public List<ValidatedOutcomeForReward> claimPendingReporterOutcomes(int limit) {
        return outcomes.claimPendingReporterRewards(limit).stream()
                .map(row -> new ValidatedOutcomeForReward(
                        historyReader.read(row.getId())
                                .orElseThrow(() -> new IllegalStateException("Missing outcome history " + row.getId())),
                        row.getOwnerUserId()))
                .toList();
    }
}
