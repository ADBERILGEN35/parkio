package com.parkio.parking.application.port;

import com.parkio.parking.fraud.FraudLedgerEntry;
import com.parkio.parking.fraud.FraudSubject;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Append-only fraud evaluation ledger boundary. */
public interface FraudLedgerPort {

    void append(FraudLedgerEntry entry);

    Optional<FraudLedgerEntry> findByEvaluationId(UUID evaluationId);

    List<FraudLedgerEntry> findBySubject(FraudSubject subject);
}
