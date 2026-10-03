package com.parkio.auth.application;

import com.parkio.auth.application.port.DurableErasureCheckpoint;
import com.parkio.auth.application.port.DurableErasureCheckpointStore;
import com.parkio.auth.application.port.ErasureLedgerCapture;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Produces one signed checkpoint of the tombstone ledger (contract stage 2): a lock-protocol
 * capture, then publication in the durable store, which reserves the checkpoint's sequence only
 * after the capture committed. It exists only when
 * {@code parkio.privacy.account-erasure.durable-store.checkpoint.enabled=true} (default false),
 * and nothing in the service calls it: the cadence is an operator decision and no schedule is
 * configured.
 */
public class ErasureCheckpointProducer {

    private static final Logger log = LoggerFactory.getLogger(ErasureCheckpointProducer.class);

    private final DurableErasureCheckpointStore store;
    private final ErasureLedgerCapture capture;

    public ErasureCheckpointProducer(DurableErasureCheckpointStore store, ErasureLedgerCapture capture) {
        this.store = Objects.requireNonNull(store, "store");
        this.capture = Objects.requireNonNull(capture, "capture");
    }

    public DurableErasureCheckpoint produce() {
        DurableErasureCheckpoint checkpoint = store.publishCheckpoint(capture);
        log.info("erasure checkpoint published sequence={} entries={} coveredThrough={} filledReservation={}",
                checkpoint.sequence(), checkpoint.entryCount(), checkpoint.coveredThrough(),
                checkpoint.filledReservation());
        return checkpoint;
    }
}
