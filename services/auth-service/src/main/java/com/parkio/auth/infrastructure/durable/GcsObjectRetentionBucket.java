package com.parkio.auth.infrastructure.durable;

import com.google.cloud.storage.StorageException;
import com.parkio.auth.domain.exception.AuthErrorCode;
import com.parkio.auth.domain.exception.AuthException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GCS Object Retention Lock implementation of {@link DurableEvidenceBucket}. Version ids are GCS
 * generation numbers as decimal strings. Canonical content is the <em>lowest</em> generation of a
 * key (oldest write), never the listing order — GCS list pages may return newest-first or be
 * paginated arbitrarily.
 *
 * <p>Conditional writes ({@link #putIfAbsent}, {@link #putIfCurrent}) send Cloud Storage's
 * {@code ifGenerationMatch} precondition: {@code 0} for an object without a live generation, else
 * the live generation read before. A precondition that no longer holds (HTTP 412) writes nothing
 * and returns empty. A write whose response is lost fails the call; the next call's conditional
 * write then finds that generation live (412), and the store resolves it by reading back.
 *
 * <p>Only this adapter resends a write. The client would retry a conditional insert, which it
 * treats as idempotent, for up to its 50 s budget, so {@link GoogleCloudStorageTransport} sends
 * every insert through a copy of the client with one attempt per request. Cloud Storage allows
 * about one write per second to one object name and answers a faster write with HTTP 429 without
 * writing it; the store writes the frontier once per record and per checkpoint, and two calls in a
 * row can meet that limit. A write refused with 429 is sent again after each of
 * {@link #RATE_LIMIT_PAUSES}, with the same bytes, precondition and retain-until; any other failure
 * fails at once. One write therefore makes at most five sends, each within the client's request
 * timeouts, and pauses at most 16.5 s. The caller keeps the store's lock meanwhile, on the request
 * thread for records after the erasure commit, so before enablement (#365, #492) the total pause
 * must be bounded per operation or that path must fail fast to the retry worker.
 *
 * <p>A listing ({@link #keys}, {@link #oldest}, {@link #allVersions}, {@link #versionCount}) also
 * runs under that lock, so it stays within {@link ListBounds} (#493, #616): it fails closed with
 * {@link AuthErrorCode#DURABLE_RECORDING_UNAVAILABLE} instead of following a page token it has
 * already followed, fetching more than the maximum number of pages, or running past its deadline.
 * Each page is requested on a virtual thread of its own and waited for at most what is left of the
 * deadline. A page still in flight then is abandoned and its thread interrupted: the request may run
 * on until the client's own timeouts end it, but no longer under the lock. A listing that completes
 * past half of either bound logs a warning, before its history outgrows the bounds.
 *
 * <p>This class does not claim real-provider immutability acceptance; that remains #483.
 */
final class GcsObjectRetentionBucket implements DurableEvidenceBucket {

    static final int LIST_PAGE_SIZE = 100;
    /** Pauses before each resend of a write refused with 429; after the last one the put fails. */
    static final List<Duration> RATE_LIMIT_PAUSES = List.of(
            Duration.ofMillis(1_100), Duration.ofMillis(2_200), Duration.ofMillis(4_400), Duration.ofMillis(8_800));

    private static final Logger log = LoggerFactory.getLogger(GcsObjectRetentionBucket.class);
    /** Page requests run on virtual threads, so a listing can stop waiting for one at its deadline. */
    private static final ThreadFactory PAGE_REQUESTS = Thread.ofVirtual().name("durable-store-list-page-", 1).factory();

    /** Waits between resends; tests replace the real sleep. */
    @FunctionalInterface
    interface Pause {
        void sleep(Duration duration) throws InterruptedException;
    }

    /**
     * Bounds one listing: how many pages of {@link GcsObjectRetentionBucket#LIST_PAGE_SIZE} generations it may fetch, and
     * how long it may take, a page in flight included. The bounds apply to each listing (one
     * {@code keys}, {@code oldest}, {@code allVersions} or {@code versionCount} call), not to a store
     * operation, which may list several times. A tool built with a constructor that takes no bounds
     * gets {@link #DEFAULT}; a recovery tool reading a longer history passes its own.
     */
    record ListBounds(int maxPages, Duration deadline) {

        /** Upper limits, ten times {@link #DEFAULT}: no configured value switches a bound off (#616). */
        static final int MAX_PAGES = 10_000;
        static final Duration MAX_DEADLINE = Duration.ofMinutes(5);

        /**
         * 1,000 pages and 30 s. The deadline binds first once pages take more than 30 ms on average,
         * so a listing completes with up to about 100 * min(maxPages, deadline / mean page time)
         * generations: 100,000 at 30 ms a page or less, 30,000 at 100 ms. Since #494 the store's
         * normal path lists no history; the long listings are recovery's and the store's fallback
         * read of the frontier, whose history is the bucket's first boundary plus one generation per
         * record and one per checkpoint (sizing: docs/operations/n02-gcs-evidence-adapter-design.md).
         * A provider that repeats or never ends its pages is stopped within the deadline, and the
         * request stays {@code PENDING_DURABLE} for the retry worker.
         */
        static final ListBounds DEFAULT = new ListBounds(1_000, Duration.ofSeconds(30));

        ListBounds {
            if (maxPages < 1) {
                throw new IllegalArgumentException("maxPages must be at least 1");
            }
            if (maxPages > MAX_PAGES) {
                throw new IllegalArgumentException("maxPages must be at most " + MAX_PAGES);
            }
            Objects.requireNonNull(deadline, "deadline");
            if (deadline.isZero() || deadline.isNegative()) {
                throw new IllegalArgumentException("deadline must be positive");
            }
            if (deadline.compareTo(MAX_DEADLINE) > 0) {
                throw new IllegalArgumentException("deadline must be at most " + MAX_DEADLINE);
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

    /** The bounds every listing of this adapter keeps. */
    ListBounds listBounds() {
        return listBounds;
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
                GcsObjectRetentionTransport.ListPage page = page(prefix, token, pages, started);
                all.addAll(page.items());
                token = page.nextPageToken();
                if (token.isEmpty()) {
                    warnPastHalfOfABound(prefix, pages, elapsedSince(started));
                    return all;
                }
                if (!followed.add(token.get())) {
                    throw listRefused(prefix, "page token repeated");
                }
                if (pages >= listBounds.maxPages()) {
                    throw listRefused(prefix, "more than " + listBounds.maxPages() + " pages");
                }
                if (elapsedSince(started).compareTo(listBounds.deadline()) >= 0) {
                    throw listRefused(prefix, "deadline " + listBounds.deadline() + " passed");
                }
            }
        });
    }

    /**
     * Page {@code number} of a listing that started at {@code started}: requested on a thread of its
     * own and waited for at most what is left of the deadline. A page still in flight then is
     * abandoned (its thread is interrupted) and the listing fails closed (#616).
     */
    private GcsObjectRetentionTransport.ListPage page(String prefix, Optional<String> token, int number, long started)
            throws Exception {
        Duration left = listBounds.deadline().minus(elapsedSince(started));
        if (left.isZero() || left.isNegative()) {
            throw listRefused(prefix, "deadline " + listBounds.deadline() + " passed");
        }
        FutureTask<GcsObjectRetentionTransport.ListPage> request =
                new FutureTask<>(() -> transport.listGenerations(prefix, token, LIST_PAGE_SIZE));
        PAGE_REQUESTS.newThread(request).start();
        try {
            return request.get(left.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException ex) {
            throw listRefused(prefix, "deadline " + listBounds.deadline() + " passed while page " + number
                    + " was in flight");
        } catch (ExecutionException ex) {
            // What the transport threw, as if it had been called on this thread.
            if (ex.getCause() instanceof Error error) {
                throw error;
            }
            throw (Exception) ex.getCause();
        } finally {
            request.cancel(true);
        }
    }

    private Duration elapsedSince(long started) {
        return Duration.ofNanos(nanoTime.getAsLong() - started);
    }

    /**
     * The early warning (#616): a listing that completed past half of either bound. Histories only
     * grow, so the next listings of this prefix need more, and the bounds can be raised before they
     * fail closed.
     */
    private void warnPastHalfOfABound(String prefix, int pages, Duration elapsed) {
        if (pages * 2L > listBounds.maxPages() || elapsed.multipliedBy(2).compareTo(listBounds.deadline()) > 0) {
            log.warn("durable store list {} needed {} of at most {} pages and {} of its {} deadline, past half of a"
                            + " bound: raise list-max-pages or list-deadline before such listings fail closed", prefix,
                    pages, listBounds.maxPages(), elapsed.truncatedTo(ChronoUnit.MILLIS), listBounds.deadline());
        }
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
