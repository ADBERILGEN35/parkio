package com.parkio.parking.infrastructure.persistence;

import com.parkio.parking.application.port.ValidatedOutcomeForTrustReadPort;
import com.parkio.parking.application.trust.ValidatedOutcomeForTrust;
import com.parkio.parking.infrastructure.persistence.jpa.OutcomeHistoryJpaRepository;
import com.parkio.parking.infrastructure.persistence.trust.OutcomeHistoryRecordReader;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ValidatedOutcomeForTrustReadRepositoryAdapter implements ValidatedOutcomeForTrustReadPort {

    private final OutcomeHistoryJpaRepository outcomes;
    private final OutcomeHistoryRecordReader historyReader;

    public ValidatedOutcomeForTrustReadRepositoryAdapter(
            OutcomeHistoryJpaRepository outcomes,
            OutcomeHistoryRecordReader historyReader) {
        this.outcomes = outcomes;
        this.historyReader = historyReader;
    }

    @Override
    public List<ValidatedOutcomeForTrust> claimPendingReporterOutcomes(int limit) {
        return outcomes.claimPendingReporterOutcomes(limit).stream()
                .map(row -> new ValidatedOutcomeForTrust(
                        historyReader.read(row.getId())
                                .orElseThrow(() -> new IllegalStateException("Missing outcome history " + row.getId())),
                        row.getOwnerUserId()))
                .toList();
    }
}

