package com.parkio.auth.infrastructure.durable;

import com.google.cloud.storage.StorageException;
import com.parkio.auth.domain.exception.AuthErrorCode;
import com.parkio.auth.domain.exception.AuthException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * GCS Object Retention Lock implementation of {@link DurableEvidenceBucket}. Version ids are GCS
 * generation numbers as decimal strings. Canonical content is the <em>lowest</em> generation of a
 * key (oldest write), never the listing order — GCS list pages may return newest-first or be
 * paginated arbitrarily.
 *
 * <p>Conditional writes ({@link #putIfAbsent}, {@link #putIfCurrent}) send Cloud Storage's
 * {@code ifGenerationMatch} precondition: {@code 0} for an object without a live generation, else
 * the live generation read before. A precondition that no longer holds (HTTP 412) writes nothing
 * and returns empty. The client library may resend such a write after a lost response; the resend
 * then finds its own generation live and ends with 412, which the store resolves by reading back.
 *
 * <p>Cloud Storage allows about one write per second to one object name and answers a faster
 * write with HTTP 429 without writing it. The store writes its frontier twice per record and per
 * checkpoint, so a refused write is sent again after {@link #RATE_LIMIT_PAUSES}; the client
 * library does not retry an unconditional insert itself, because it is not idempotent. The
 * resend keeps the bytes, the precondition and the retain-until computed for the first send. While
 * it waits (up to 16.5 s per write) the caller keeps the store's lock; the record path runs after
 * the erasure commit on the request thread, so before enablement (#365, #492) the total pause must
 * be bounded per operation or that path must fail fast to the retry worker.
 *
 * <p>A listing ({@link #keys}, {@link #oldest}, {@link #allVersions}, {@link #versionCount}) also
 * runs under that lock, so it stays within {@link ListBounds} (#493): it fails closed with
 * {@link AuthErrorCode#DURABLE_RECORDING_UNAVAILABLE} instead of following a page token it has
 * already followed, fetching more than the maximum number of pages, or requesting another page once
 * its deadline has passed.
 *
 * <p>This class does not claim real-provider immutability acceptance; that remains #483.
 */
final class GcsObjectRetentionBucket implements DurableEvidenceBucket {

    static final int LIST_PAGE_SIZE = 100;
    /** Pauses before each resend of a write refused with 429; after the last one the put fails. */
    static final List<Duration> RATE_LIMIT_PAUSES = List.of(
            Duration.ofMillis(1_100), Duration.ofMillis(2_200), Duration.ofMillis(4_400), Duration.ofMillis(8_800));

    /** Waits between resends; tests replace the real sleep. */
    @FunctionalInterface
    interface Pause {
        void sleep(Duration duration) throws InterruptedException;
    }

    /**
     * Bounds one listing: how many pages of {@link GcsObjectRetentionBucket#LIST_PAGE_SIZE} generations it may fetch, and
     * for how long after it started it may still request another page. The deadline is checked
     * between pages; a request in flight is bounded by the client's own timeouts.
     */
    record ListBounds(int maxPages, Duration deadline) {

        /**
         * 100,000 generations: the frontier history of about 50,000 records at two frontier writes
         * per record, far above erasure volumes, while a provider that repeats or never ends its
         * pages is stopped within 30 s and the request stays {@code PENDING_DURABLE} for the retry
         * worker.
         */
        static final ListBounds DEFAULT = new ListBounds(1_000, Duration.ofSeconds(30));

        ListBounds {
            if (maxPages < 1) {
                throw new IllegalArgumentException("maxPages must be at least 1");
            }
            Objects.requireNonNull(deadline, "deadline");
            if (deadline.isZero() || deadline.isNegative()) {
                throw new IllegalArgumentException("deadline must be positive");
            }
        }
    }

    private final GcsObjectRetentionTransport transport;
    private final Pause pause;
    private final ListBounds listBounds;
    private final LongSupplier nanoTime;
    private final ConcurrentHashMap<String, StoredVersion> versionCache = new ConcurrentHashMap<>();

    GcsObjectRetentionBucket(GcsObjectRetentionTransport transport) {
        this(transport, ListBounds.DEFAULT);
    }

    GcsObjectRetentionBucket(GcsObjectRetentionTransport transport, ListBounds listBounds) {
        this(transport, duration -> Thread.sleep(duration.toMillis()), listBounds, System::nanoTime);
    }

    GcsObjectRetentionBucket(GcsObjectRetentionTransport transport, Pause pause) {
        this(transport, pause, ListBounds.DEFAULT, System::nanoTime);
    }

    /** {@code nanoTime} is a monotonic clock in nanoseconds; tests replace {@link System#nanoTime()}. */
    GcsObjectRetentionBucket(GcsObjectRetentionTransport transport, Pause pause, ListBounds listBounds,
                             LongSupplier nanoTime) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.pause = Objects.requireNonNull(pause, "pause");
        this.listBounds = Objects.requireNonNull(listBounds, "listBounds");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    @Override
    public String bucket() {
        return transport.bucket();
    }

    @Override
    public void requireRetentionFeature() {
        call("object retention configuration", () -> {
            try {
                transport.requireObjectRetentionEnabled();
            } catch (IllegalStateException ex) {
                // The transport's own refusal names only the bucket and the missing feature.
                throw new AuthException(AuthErrorCode.DURABLE_RECORDING_UNAVAILABLE,
                        "durable store object retention configuration failed: " + ex.getMessage());
            }
            return null;
        });
    }

    @Override
    public List<String> keys(String prefix) {
        TreeSet<String> keys = new TreeSet<>();
        for (GcsObjectRetentionTransport.ListedGeneration item : listAll(prefix)) {
            if (!item.deleteMarker()) {
                keys.add(item.objectName());
            }
        }
        return List.copyOf(keys);
    }

    @Override
    public Optional<StoredVersion> oldest(String key) {
        List<Long> generations = generationsOf(key);
        if (generations.isEmpty()) {
            return Optional.empty();
        }
        long oldest = generations.get(0);
        return Optional.of(read(key, oldest));
    }

    @Override
    public List<StoredVersion> allVersions(String key) {
        List<StoredVersion> all = new ArrayList<>();
        // Preserve listing encounter order for frontier multi-version verification; canonical
        // selection still uses oldest()/lowest generation elsewhere.
        for (GcsObjectRetentionTransport.ListedGeneration item : listAll(key)) {
            if (item.objectName().equals(key) && !item.deleteMarker()) {
                all.add(read(key, item.generation()));
            }
        }
        return all;
    }

    @Override
    public int versionCount(String key) {
        return generationsOf(key).size();
    }

    @Override
    public String put(String key, byte[] bytes, EvidenceRetentionMode mode, ZonedDateTime retainUntil) {
        return Long.toString(create(key, bytes, mode, retainUntil, OptionalLong.empty()).orElseThrow());
    }

    @Override
    public Optional<Current> current(String key) {
        return call("get live " + key, () -> transport.liveGeneration(key)).map(live -> {
            StoredVersion version = new StoredVersion(key, Long.toString(live.generation()), live.bytes());
            versionCache.putIfAbsent(key + "\n" + live.generation(), version);
            return new Current(version, version.versionId());
        });
    }

    @Override
    public Optional<Written> putIfAbsent(String key, byte[] bytes, EvidenceRetentionMode mode,
                                         ZonedDateTime retainUntil) {
        return written(key, bytes, create(key, bytes, mode, retainUntil, OptionalLong.of(0)));
    }

    @Override
    public Optional<Written> putIfCurrent(String key, byte[] bytes, String matchToken, EvidenceRetentionMode mode,
                                          ZonedDateTime retainUntil) {
        long live;
        try {
            live = Long.parseLong(matchToken);
        } catch (NumberFormatException ex) {
            live = 0;
        }
        if (live <= 0) {
            throw new AuthException(AuthErrorCode.DURABLE_RECORDING_UNAVAILABLE,
                    "durable store put " + key + " failed: invalid generation");
        }
        return written(key, bytes, create(key, bytes, mode, retainUntil, OptionalLong.of(live)));
    }

    /** A version this process wrote is cached, so reading it back costs no request. */
    private Optional<Written> written(String key, byte[] bytes, OptionalLong generation) {
        if (generation.isEmpty()) {
            return Optional.empty();
        }
        String versionId = Long.toString(generation.getAsLong());
        versionCache.putIfAbsent(key + "\n" + versionId, new StoredVersion(key, versionId, bytes.clone()));
        return Optional.of(new Written(versionId, versionId));
    }

    /** The new generation, or empty when {@code ifGenerationMatch} did not hold (HTTP 412). */
    private OptionalLong create(String key, byte[] bytes, EvidenceRetentionMode mode, ZonedDateTime retainUntil,
                                OptionalLong ifGenerationMatch) {
        return call("put " + key, () -> {
            for (int resends = 0; ; resends++) {
                try {
                    return OptionalLong.of(transport.createGeneration(key, bytes, mode, retainUntil.toInstant(),
                            ifGenerationMatch));
                } catch (StorageException ex) {
                    if (ex.getCode() == GcsObjectRetentionTransport.PRECONDITION_FAILED
                            && ifGenerationMatch.isPresent()) {
                        return OptionalLong.empty();
                    }
                    if (ex.getCode() != 429 || resends == RATE_LIMIT_PAUSES.size()) {
                        throw ex;
                    }
                    pause.sleep(RATE_LIMIT_PAUSES.get(resends));
                }
            }
        });
    }

    @Override
    public RetentionLock retention(String key, String versionId) {
        long generation = parseGeneration(key, versionId);
        GcsObjectRetentionTransport.RetentionState state = call("retention " + key,
                () -> transport.retention(key, generation));
        return new RetentionLock(state.mode(), state.retainUntil());
    }

    private List<Long> generationsOf(String key) {
        TreeSet<Long> generations = new TreeSet<>();
        for (GcsObjectRetentionTransport.ListedGeneration item : listAll(key)) {
            if (item.objectName().equals(key) && !item.deleteMarker()) {
                generations.add(item.generation());
            }
        }
        return List.copyOf(generations);
    }

    private List<GcsObjectRetentionTransport.ListedGeneration> listAll(String prefix) {
        return call("list " + prefix, () -> {
            List<GcsObjectRetentionTransport.ListedGeneration> all = new ArrayList<>();
            Set<String> followed = new HashSet<>();
            long started = nanoTime.getAsLong();
            Optional<String> token = Optional.empty();
            for (int pages = 1; ; pages++) {
                GcsObjectRetentionTransport.ListPage page = transport.listGenerations(prefix, token, LIST_PAGE_SIZE);
                all.addAll(page.items());
                token = page.nextPageToken();
                if (token.isEmpty()) {
                    return all;
                }
                if (!followed.add(token.get())) {
                    throw listRefused(prefix, "page token repeated");
                }
                if (pages >= listBounds.maxPages()) {
                    throw listRefused(prefix, "more than " + listBounds.maxPages() + " pages");
                }
                if (Duration.ofNanos(nanoTime.getAsLong() - started).compareTo(listBounds.deadline()) >= 0) {
                    throw listRefused(prefix, "deadline " + listBounds.deadline() + " passed");
                }
            }
        });
    }

    /** The page token itself is opaque provider state and stays out of the message. */
    private static AuthException listRefused(String prefix, String reason) {
        return new AuthException(AuthErrorCode.DURABLE_RECORDING_UNAVAILABLE,
                "durable store list " + prefix + " failed: " + reason);
    }

    private StoredVersion read(String key, long generation) {
        String cacheKey = key + "\n" + generation;
        return versionCache.computeIfAbsent(cacheKey, ignored -> call("get " + key, () -> {
            byte[] bytes = transport.readGeneration(key, generation);
            return new StoredVersion(key, Long.toString(generation), bytes);
        }));
    }

    private static long parseGeneration(String key, String versionId) {
        try {
            return Long.parseLong(versionId);
        } catch (NumberFormatException ex) {
            throw new AuthException(AuthErrorCode.DURABLE_RECORDING_UNAVAILABLE,
                    "durable store retention " + key + " failed: invalid generation");
        }
    }

    @FunctionalInterface
    private interface StoreCall<T> {
        T run() throws Exception;
    }

    private static <T> T call(String operation, StoreCall<T> call) {
        try {
            return call.run();
        } catch (AuthException ex) {
            throw ex;
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new AuthException(AuthErrorCode.DURABLE_RECORDING_UNAVAILABLE,
                    "durable store " + operation + " failed: " + ex.getClass().getSimpleName());
        }
    }

    /** Lowest generation first — independent of list encounter order. */
    static Comparator<Long> generationAscending() {
        return Long::compare;
    }
}
