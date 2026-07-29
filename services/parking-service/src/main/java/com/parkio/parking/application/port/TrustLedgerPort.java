package com.parkio.parking.application.port;

import com.parkio.parking.trust.TrustLedgerEntry;
import com.parkio.parking.trust.TrustSubject;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Append-only ledger boundary for trust shadow. */
public interface TrustLedgerPort {

    void append(TrustLedgerEntry entry);

    Optional<TrustLedgerEntry> findByEvaluationId(UUID evaluationId);

    List<TrustLedgerEntry> findBySubject(TrustSubject subject);
}

