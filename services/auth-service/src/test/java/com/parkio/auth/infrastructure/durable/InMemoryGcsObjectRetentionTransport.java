package com.parkio.auth.infrastructure.durable;

import com.parkio.auth.domain.exception.AuthErrorCode;
import com.parkio.auth.domain.exception.AuthException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Controlled GCS-like transport for local behavioural tests. Models generation numbers, newest-first
 * listing with pagination, Object Retention Lock modes, and permission/unavailable failures.
 * <strong>Not</strong> real-provider acceptance (#483).
 */
final class InMemoryGcsObjectRetentionTransport implements GcsObjectRetentionTransport {

    private final String bucket;
    private final AtomicLong generationSeq = new AtomicLong(1_000);
    private final Map<String, List<Stored>> objects = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private boolean objectRetentionEnabled = true;
    private boolean versioningEnabled = true;
    private boolean unavailable;
    private boolean missingListPermission;
    private boolean missingWritePermission;
    private boolean listNewestFirst = true;
    private int forcedPageSizeOverride = -1;

    InMemoryGcsObjectRetentionTransport(String bucket) {
        this.bucket = Objects.requireNonNull(bucket, "bucket");
    }

    void setObjectRetentionEnabled(boolean enabled) {
        this.objectRetentionEnabled = enabled;
    }

    void setVersioningEnabled(boolean enabled) {
        this.versioningEnabled = enabled;
    }

    void setUnavailable(boolean unavailable) {
        this.unavailable = unavailable;
    }

    void setMissingListPermission(boolean missing) {
        this.missingListPermission = missing;
    }

    void setMissingWritePermission(boolean missing) {
        this.missingWritePermission = missing;
    }

    void setListNewestFirst(boolean newestFirst) {
        this.listNewestFirst = newestFirst;
    }

    /** When positive, list pages are capped at this size regardless of the caller page size. */
    void setForcedPageSizeOverride(int pageSize) {
        this.forcedPageSizeOverride = pageSize;
    }

    @Override
    public String bucket() {
        return bucket;
    }

    @Override
    public void requireObjectRetentionEnabled() {
        failIfUnavailable();
        if (!objectRetentionEnabled) {
            throw new IllegalStateException("bucket " + bucket + " does not have Object Retention Lock enabled");
        }
        if (!versioningEnabled) {
            throw new IllegalStateException("bucket " + bucket + " does not have Object Versioning enabled");
        }
    }

    @Override
    public long createGeneration(String objectName, byte[] bytes, EvidenceRetentionMode mode, Instant retainUntil) {
        failIfUnavailable();
        if (missingWritePermission) {
            throw new AuthException(AuthErrorCode.DURABLE_RECORDING_UNAVAILABLE,
                    "durable store put " + objectName + " failed: PermissionDenied");
        }
        lock.lock();
        try {
            long generation = generationSeq.getAndIncrement();
            objects.computeIfAbsent(objectName, ignored -> new ArrayList<>())
                    .add(new Stored(generation, bytes.clone(), mode, retainUntil));
            return generation;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public RetentionState retention(String objectName, long generation) {
        failIfUnavailable();
        Stored stored = find(objectName, generation)
                .orElseThrow(() -> new IllegalStateException("missing object retention on " + objectName + "#" + generation));
        return new RetentionState(stored.mode, stored.retainUntil);
    }

    @Override
    public ListPage listGenerations(String prefix, Optional<String> pageToken, int pageSize) {
        failIfUnavailable();
        if (missingListPermission) {
            throw new AuthException(AuthErrorCode.DURABLE_RECORDING_UNAVAILABLE,
                    "durable store list " + prefix + " failed: PermissionDenied");
        }
        int size = forcedPageSizeOverride > 0 ? forcedPageSizeOverride : pageSize;
        List<ListedGeneration> flat = new ArrayList<>();
        lock.lock();
        try {
            for (Map.Entry<String, List<Stored>> entry : new LinkedHashMap<>(objects).entrySet()) {
                if (!entry.getKey().startsWith(prefix) && !entry.getKey().equals(prefix)) {
                    continue;
                }
                List<Stored> gens = new ArrayList<>(entry.getValue());
                gens.sort(listNewestFirst
                        ? Comparator.comparingLong((Stored s) -> s.generation).reversed()
                        : Comparator.comparingLong(s -> s.generation));
                for (Stored stored : gens) {
                    flat.add(new ListedGeneration(entry.getKey(), stored.generation, false));
                }
            }
        } finally {
            lock.unlock();
        }
        int start = pageToken.map(Integer::parseInt).orElse(0);
        if (start < 0 || start > flat.size()) {
            return new ListPage(List.of(), Optional.empty());
        }
        int end = Math.min(start + size, flat.size());
        List<ListedGeneration> page = List.copyOf(flat.subList(start, end));
        Optional<String> next = end < flat.size() ? Optional.of(Integer.toString(end)) : Optional.empty();
        return new ListPage(page, next);
    }

    @Override
    public byte[] readGeneration(String objectName, long generation) {
        failIfUnavailable();
        return find(objectName, generation)
                .map(stored -> stored.bytes.clone())
                .orElseThrow(() -> new IllegalStateException("missing generation " + objectName + "#" + generation));
    }

    /** Attempts to shorten COMPLIANCE retain-until; returns whether the stub refused. */
    boolean tryShortenCompliance(String objectName, long generation, Instant earlier) {
        lock.lock();
        try {
            Stored stored = find(objectName, generation).orElseThrow();
            if (stored.mode == EvidenceRetentionMode.COMPLIANCE && earlier.isBefore(stored.retainUntil)) {
                return false;
            }
            stored.retainUntil = earlier;
            return true;
        } finally {
            lock.unlock();
        }
    }

    private Optional<Stored> find(String objectName, long generation) {
        lock.lock();
        try {
            List<Stored> gens = objects.get(objectName);
            if (gens == null) {
                return Optional.empty();
            }
            return gens.stream().filter(s -> s.generation == generation).findFirst();
        } finally {
            lock.unlock();
        }
    }

    private void failIfUnavailable() {
        if (unavailable) {
            throw new AuthException(AuthErrorCode.DURABLE_RECORDING_UNAVAILABLE,
                    "durable store unavailable failed: StorageUnavailable");
        }
    }

    private static final class Stored {
        final long generation;
        final byte[] bytes;
        final EvidenceRetentionMode mode;
        Instant retainUntil;

        Stored(long generation, byte[] bytes, EvidenceRetentionMode mode, Instant retainUntil) {
            this.generation = generation;
            this.bytes = bytes;
            this.mode = mode;
            this.retainUntil = retainUntil;
        }
    }
}
