package com.parkio.auth.infrastructure.durable;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.auth.application.durable.DurableErasureEvidence;
import com.parkio.auth.application.durable.ErasureLedgerEntry;
import com.parkio.auth.application.durable.EvidenceTrust;
import com.parkio.auth.application.durable.TrustedKey;
import com.parkio.auth.application.port.CapturedErasureLedger;
import com.parkio.auth.application.port.DurableErasureRecord;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Requests per store operation with 1,000 and then 10,000 recorded erasures (#494), on the GCS
 * adapter over the in-memory transport. The history is written straight to the transport in the
 * store's own format: per erasure a sequence marker, the signed pending record and the two frontier
 * generations that {@code putIfAbsent} leaves. Only the measured operations go through the store,
 * and every transport call they make is counted.
 */
class ObjectLockDurableErasureRecordStoreScaleTest {

    /** Well above what one operation needs; far below one request per recorded erasure. */
    private static final int MAX_REQUESTS_PER_OPERATION = 25;

    private static final String DATABASE = "postgresql:7000000000000000009:parkio_auth";
    private static final byte[] SECRET = "durable-scale-test-hmac-key-not-a-secret!!".getBytes(StandardCharsets.UTF_8);
    private static final TrustedKey PRODUCER = TrustedKey.active("scale-test-key-2026a", "auth-scale-test", SECRET,
            Instant.parse("2026-01-01T00:00:00Z"));
    private static final EvidenceTrust TRUST = new EvidenceTrust(DATABASE, List.of(PRODUCER));
    private static final Duration RETENTION = Duration.ofHours(2);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-24T08:00:00Z"), ZoneOffset.UTC);
    private static final Instant ERASED_AT = Instant.parse("2026-11-24T07:00:00Z");

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void storeOperationsCostTheSameNumberOfRequestsAtOneThousandAndTenThousandRecordedErasures() {
        InMemoryGcsObjectRetentionTransport memory = new InMemoryGcsObjectRetentionTransport("parkio-erasure-scale");
        CountingTransport counting = new CountingTransport(memory);
        ObjectLockDurableErasureRecordStore store = new ObjectLockDurableErasureRecordStore(
                new GcsObjectRetentionBucket(counting), TRUST, PRODUCER.keyId(), EvidenceRetentionMode.GOVERNANCE,
                RETENTION, CLOCK);

        writeHistory(memory, 1, 1_000);
        Map<String, Integer> atOneThousand = measure(store, counting, historicRecord(500));
        System.out.println("requests per operation with 1,000 recorded erasures: " + atOneThousand);
        assertThat(atOneThousand.values()).allSatisfy(requests ->
                assertThat(requests).isLessThanOrEqualTo(MAX_REQUESTS_PER_OPERATION));

        // The measured put and checkpoint took the next two sequences; the history continues above them.
        writeHistory(memory, highestMarker(memory) + 1, 10_000);
        Map<String, Integer> atTenThousand = measure(store, counting, historicRecord(9_000));
        System.out.println("requests per operation with 10,000 recorded erasures: " + atTenThousand);
        assertThat(atTenThousand).isEqualTo(atOneThousand);
    }

    /**
     * One request of each kind, counted separately: a new record, an identical retry, a lookup of
     * a recorded erasure and a checkpoint.
     */
    private static Map<String, Integer> measure(ObjectLockDurableErasureRecordStore store, CountingTransport counting,
                                                DurableErasureRecord recorded) {
        Map<String, Integer> requests = new LinkedHashMap<>();
        DurableErasureRecord fresh = DurableErasureRecord.of(UUID.randomUUID(), UUID.randomUUID(), ERASED_AT);

        counting.reset();
        assertThat(store.putIfAbsent(fresh).created()).isTrue();
        requests.put("putIfAbsent", counting.requests());

        counting.reset();
        assertThat(store.putIfAbsent(fresh).created()).isFalse();
        requests.put("putIfAbsent retry", counting.requests());

        counting.reset();
        assertThat(store.findByRequestId(recorded.erasureRequestId())).contains(recorded);
        requests.put("findByRequestId", counting.requests());

        counting.reset();
        assertThat(store.publishCheckpoint(() -> new CapturedErasureLedger(
                List.of(new ErasureLedgerEntry(fresh.authUserId(), ERASED_AT)), CLOCK.instant())).entryCount())
                .isEqualTo(1);
        requests.put("publishCheckpoint", counting.requests());
        return requests;
    }

    /** Erasures {@code first} through {@code last}, each as {@code putIfAbsent} leaves it. */
    private static void writeHistory(InMemoryGcsObjectRetentionTransport memory, long first, long last) {
        for (long sequence = first; sequence <= last; sequence++) {
            DurableErasureRecord record = historicRecord(sequence);
            write(memory, DurableErasureEvidence.sequenceKey(sequence),
                    DurableErasureEvidence.sequenceMarker(sequence, record.erasureRequestId()));
            write(memory, DurableErasureEvidence.FRONTIER_KEY,
                    DurableErasureEvidence.frontier(sequence - 1, sequence, DATABASE, PRODUCER));
            write(memory, DurableErasureEvidence.recordKey(record.erasureRequestId()),
                    DurableErasureEvidence.pendingRecord(record, sequence, DATABASE, PRODUCER));
            write(memory, DurableErasureEvidence.FRONTIER_KEY,
                    DurableErasureEvidence.frontier(sequence, sequence, DATABASE, PRODUCER));
        }
    }

    private static DurableErasureRecord historicRecord(long sequence) {
        return DurableErasureRecord.of(
                UUID.nameUUIDFromBytes(("scale-request-" + sequence).getBytes(StandardCharsets.UTF_8)),
                UUID.nameUUIDFromBytes(("scale-user-" + sequence).getBytes(StandardCharsets.UTF_8)), ERASED_AT);
    }

    private static void write(InMemoryGcsObjectRetentionTransport memory, String key, byte[] bytes) {
        memory.createGeneration(key, bytes, EvidenceRetentionMode.GOVERNANCE, CLOCK.instant().plus(RETENTION));
    }

    private static long highestMarker(InMemoryGcsObjectRetentionTransport memory) {
        List<String> markers = new GcsObjectRetentionBucket(memory).keys("sequences/");
        String last = markers.get(markers.size() - 1);
        return Long.parseLong(last.substring("sequences/".length(), last.length() - ".json".length()));
    }

    /** Counts every call the adapter makes; one call is one provider request. */
    private static final class CountingTransport implements GcsObjectRetentionTransport {

        private final GcsObjectRetentionTransport delegate;
        private int requests;

        CountingTransport(GcsObjectRetentionTransport delegate) {
            this.delegate = delegate;
        }

        void reset() {
            requests = 0;
        }

        int requests() {
            return requests;
        }

        @Override
        public String bucket() {
            return delegate.bucket();
        }

        @Override
        public void requireObjectRetentionEnabled() {
            requests++;
            delegate.requireObjectRetentionEnabled();
        }

        @Override
        public long createGeneration(String objectName, byte[] bytes, EvidenceRetentionMode mode,
                                     Instant retainUntil) {
            requests++;
            return delegate.createGeneration(objectName, bytes, mode, retainUntil);
        }

        @Override
        public RetentionState retention(String objectName, long generation) {
            requests++;
            return delegate.retention(objectName, generation);
        }

        @Override
        public ListPage listGenerations(String prefix, Optional<String> pageToken, int pageSize) {
            requests++;
            return delegate.listGenerations(prefix, pageToken, pageSize);
        }

        @Override
        public byte[] readGeneration(String objectName, long generation) {
            requests++;
            return delegate.readGeneration(objectName, generation);
        }
    }
}
