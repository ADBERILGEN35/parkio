package com.parkio.auth.infrastructure.durable;

import com.parkio.auth.infrastructure.durable.GcsAcceptanceProvider.Identity;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Wraps the merged adapter's transport for the #483 harness without changing what it sends: each
 * call is checked against the run's caps, counted, and its outcome (provider status included) kept
 * for the cases. One optional fault, a response lost after the provider committed a write, is
 * injected <em>after</em> the real call returned, so the generation exists exactly as on a timeout.
 * Optional client-side pacing spaces writes to one object name; it is off unless a case turns it
 * on, so the store is otherwise measured as merged.
 */
final class InstrumentedGcsTransport implements GcsObjectRetentionTransport {

    /** A write the provider committed whose response the caller never received. */
    static final class SimulatedLostResponse extends RuntimeException {
        SimulatedLostResponse(String objectName) {
            super("simulated lost response after the provider committed " + objectName);
        }
    }

    /** One list page as the provider returned it. */
    record ListedPage(String prefix, int requestedPageSize, int items, boolean hasNextPage) {
    }

    private final GcsObjectRetentionTransport delegate;
    private final Identity identity;
    private final GcsAcceptanceLedger ledger;
    private final List<ListedPage> pages = new ArrayList<>();
    private final GcsAcceptanceProvider provider;
    private final Map<String, Instant> lastWrite = new HashMap<>();
    private String loseResponseFor;
    private Long lostResponseGeneration;
    private Duration spacing;

    InstrumentedGcsTransport(GcsObjectRetentionTransport delegate, Identity identity, GcsAcceptanceLedger ledger,
                             GcsAcceptanceProvider provider) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    /** The next successful write of {@code objectName} commits, then its response is dropped. */
    synchronized void loseResponseAfterCommitOnce(String objectName) {
        this.loseResponseFor = Objects.requireNonNull(objectName, "objectName");
        this.lostResponseGeneration = null;
    }

    /** Waits before a write until {@code spacing} has passed since this transport last wrote that name. */
    synchronized void paceSameObjectWrites(Duration spacing) {
        this.spacing = Objects.requireNonNull(spacing, "spacing");
    }

    synchronized void stopPacing() {
        this.spacing = null;
    }

    synchronized Long lostResponseGeneration() {
        return lostResponseGeneration;
    }

    synchronized List<ListedPage> pages() {
        return List.copyOf(pages);
    }

    @Override
    public String bucket() {
        return delegate.bucket();
    }

    @Override
    public void requireObjectRetentionEnabled() {
        call("buckets.get", () -> {
            delegate.requireObjectRetentionEnabled();
            return null;
        });
    }

    @Override
    public long createGeneration(String objectName, byte[] bytes, EvidenceRetentionMode mode, Instant retainUntil) {
        if (mode == EvidenceRetentionMode.COMPLIANCE) {
            ledger.beforeComplianceWrite();
        }
        pace(objectName);
        long generation = call("objects.insert", () -> delegate.createGeneration(objectName, bytes, mode, retainUntil));
        ledger.created(objectName, generation, mode, retainUntil, bytes.length);
        synchronized (this) {
            lastWrite.put(objectName, provider.clock().instant());
            if (objectName.equals(loseResponseFor)) {
                loseResponseFor = null;
                lostResponseGeneration = generation;
                throw new SimulatedLostResponse(objectName);
            }
        }
        return generation;
    }

    @Override
    public RetentionState retention(String objectName, long generation) {
        return call("objects.get", () -> delegate.retention(objectName, generation));
    }

    @Override
    public ListPage listGenerations(String prefix, Optional<String> pageToken, int pageSize) {
        ListPage page = call("objects.list", () -> delegate.listGenerations(prefix, pageToken, pageSize));
        synchronized (this) {
            pages.add(new ListedPage(prefix, pageSize, page.items().size(), page.nextPageToken().isPresent()));
        }
        return page;
    }

    @Override
    public byte[] readGeneration(String objectName, long generation) {
        return call("objects.get.media", () -> delegate.readGeneration(objectName, generation));
    }

    private void pace(String objectName) {
        Duration wait;
        synchronized (this) {
            Instant last = lastWrite.get(objectName);
            if (spacing == null || last == null) {
                return;
            }
            wait = spacing.minus(Duration.between(last, provider.clock().instant()));
        }
        if (!wait.isNegative() && !wait.isZero()) {
            try {
                provider.sleep(wait);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while pacing writes", ex);
            }
        }
    }

    private <T> T call(String operation, Supplier<T> body) {
        ledger.beforeOperation(operation);
        try {
            T result = body.get();
            ledger.afterOperation(operation, identity, null);
            return result;
        } catch (RuntimeException ex) {
            ledger.afterOperation(operation, identity, ex);
            throw ex;
        }
    }
}
