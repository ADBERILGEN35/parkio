package com.parkio.auth.application.support;

import com.parkio.auth.application.port.DurableErasurePutResult;
import com.parkio.auth.application.port.DurableErasureRecord;
import com.parkio.auth.application.port.DurableErasureRecordStore;
import com.parkio.auth.domain.exception.AuthErrorCode;
import com.parkio.auth.domain.exception.AuthException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Test-only adapter. Not a production durability provider and not a local
 * directory store.
 */
public final class InMemoryDurableErasureRecordStore implements DurableErasureRecordStore {

    private final ConcurrentHashMap<UUID, DurableErasureRecord> records = new ConcurrentHashMap<>();
    private final AtomicInteger remainingFailures = new AtomicInteger(0);
    private volatile boolean lastPutSawActiveTransaction;

    public void failNextPuts(int count) {
        remainingFailures.set(count);
    }

    public void clear() {
        records.clear();
        remainingFailures.set(0);
        lastPutSawActiveTransaction = false;
    }

    public boolean lastPutSawActiveTransaction() {
        return lastPutSawActiveTransaction;
    }

    public int size() {
        return records.size();
    }

    @Override
    public DurableErasurePutResult putIfAbsent(DurableErasureRecord record) {
        lastPutSawActiveTransaction = TransactionSynchronizationManager.isActualTransactionActive();
        if (remainingFailures.get() > 0 && remainingFailures.getAndDecrement() > 0) {
            throw new AuthException(AuthErrorCode.DURABLE_RECORDING_UNAVAILABLE, "durable persist failed");
        }
        DurableErasureRecord existing = records.putIfAbsent(record.erasureRequestId(), record);
        if (existing == null) {
            return DurableErasurePutResult.created(record);
        }
        if (!existing.bodyDigest().equals(record.bodyDigest())) {
            return DurableErasurePutResult.conflict(existing);
        }
        return DurableErasurePutResult.existing(existing);
    }

    @Override
    public Optional<DurableErasureRecord> findByRequestId(UUID erasureRequestId) {
        return Optional.ofNullable(records.get(erasureRequestId));
    }
}
