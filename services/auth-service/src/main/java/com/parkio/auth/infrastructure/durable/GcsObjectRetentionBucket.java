package com.parkio.auth.infrastructure.durable;

import com.parkio.auth.domain.exception.AuthErrorCode;
import com.parkio.auth.domain.exception.AuthException;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GCS Object Retention Lock implementation of {@link DurableEvidenceBucket}. Version ids are GCS
 * generation numbers as decimal strings. Canonical content is the <em>lowest</em> generation of a
 * key (oldest write), never the listing order — GCS list pages may return newest-first or be
 * paginated arbitrarily.
 *
 * <p>This class does not claim real-provider immutability acceptance; that remains #483.
 */
final class GcsObjectRetentionBucket implements DurableEvidenceBucket {

    static final int LIST_PAGE_SIZE = 100;

    private final GcsObjectRetentionTransport transport;
    private final ConcurrentHashMap<String, StoredVersion> versionCache = new ConcurrentHashMap<>();

    GcsObjectRetentionBucket(GcsObjectRetentionTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    @Override
    public String bucket() {
        return transport.bucket();
    }

    @Override
    public void requireRetentionFeature() {
        call("object retention configuration", () -> {
            transport.requireObjectRetentionEnabled();
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
        long generation = call("put " + key,
                () -> transport.createGeneration(key, bytes, mode, retainUntil.toInstant()));
        return Long.toString(generation);
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
            Optional<String> token = Optional.empty();
            do {
                GcsObjectRetentionTransport.ListPage page = transport.listGenerations(prefix, token, LIST_PAGE_SIZE);
                all.addAll(page.items());
                token = page.nextPageToken();
            } while (token.isPresent());
            return all;
        });
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
