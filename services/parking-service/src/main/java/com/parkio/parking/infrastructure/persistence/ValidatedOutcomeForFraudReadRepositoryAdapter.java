package com.parkio.parking.infrastructure.persistence;

import com.parkio.parking.application.fraud.ValidatedOutcomeForFraud;
import com.parkio.parking.application.port.ValidatedOutcomeForFraudReadPort;
import com.parkio.parking.infrastructure.persistence.jpa.OutcomeHistoryJpaRepository;
import com.parkio.parking.infrastructure.persistence.trust.OutcomeHistoryRecordReader;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ValidatedOutcomeForFraudReadRepositoryAdapter implements ValidatedOutcomeForFraudReadPort {

    private final OutcomeHistoryJpaRepository outcomes;
    private final OutcomeHistoryRecordReader historyReader;

    public ValidatedOutcomeForFraudReadRepositoryAdapter(
            OutcomeHistoryJpaRepository outcomes,
            OutcomeHistoryRecordReader historyReader) {
        this.outcomes = outcomes;
        this.historyReader = historyReader;
    }

    @Override
    public List<ValidatedOutcomeForFraud> claimPendingReporterFraudCandidates(int limit) {
        return outcomes.claimPendingReporterFraudCandidates(limit).stream()
                .map(row -> new ValidatedOutcomeForFraud(
                        historyReader.read(row.getId())
                                .orElseThrow(() -> new IllegalStateException("Missing outcome history " + row.getId())),
                        row.getOwnerUserId()))
                .toList();
    }
}
