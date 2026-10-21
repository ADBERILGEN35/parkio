package com.parkio.auth.infrastructure.durable;

import com.google.cloud.storage.StorageException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * In-process model of the approved #483 operation, for {@code plan} and the harness's own tests.
 * It models what the documentation states and the run must confirm live: per-identity IAM as in the
 * proposal's custom roles, versioned overwrite needing {@code storage.objects.delete}, Unlocked and
 * Locked retention rules, deletion blocked until retain-until, one write and one metadata update per
 * second per object name, and paged generation listing. A virtual clock makes waits instant.
 * <strong>Never</strong> #483 evidence: emulators and models do not count.
 */
final class OfflineGcsAcceptanceProvider implements GcsAcceptanceProvider {

    /** Provider misbehaviour the harness must report as a failure (mutation checks of the harness). */
    enum Defect {
        LOCKED_RETENTION_SHORTENABLE,
        RETAINED_DELETABLE,
        LIST_DROPS_PAGE_TAIL,
        READER_MAY_CREATE,
        CONTROL_BUCKET_HAS_RETENTION_LOCK,
        VERSIONING_DISABLED,
        GENERATIONS_DECREASE,
        PROBE_ADMIN_WITHOUT_BUCKET_GET
    }

    static final String PROJECT_NUMBER = "100000000001";
    static final Map<String, String> LABELS = Map.of(
            "parkio-purpose", "n02-gcs-acceptance", "parkio-data", "synthetic", "parkio-issue", "483");

    private static final Set<String> WRITER = Set.of("storage.buckets.get", "storage.objects.create",
            "storage.objects.delete", "storage.objects.get", "storage.objects.list", "storage.objects.setRetention");
    private static final Set<String> WRITER_NO_DELETE = Set.of("storage.buckets.get", "storage.objects.create",
            "storage.objects.get", "storage.objects.list", "storage.objects.setRetention");
    private static final Set<String> READER = Set.of("storage.buckets.get", "storage.objects.get",
            "storage.objects.list");
    private static final Set<String> PROBE_ADMIN = Set.of("storage.buckets.get", "storage.objects.get",
            "storage.objects.update", "storage.objects.delete", "storage.objects.setRetention",
            "storage.objects.overrideUnlockedRetention");

    private final VirtualClock clock;
    private final Set<Defect> defects;
    private final Map<String, BucketModel> buckets = new HashMap<>();
    private final Map<String, Map<Identity, Set<String>>> grants = new HashMap<>();
    private long lastGeneration;

    OfflineGcsAcceptanceProvider(Instant start, String bucket, String controlBucket, String location,
                                 Set<Defect> defects) {
        this.clock = new VirtualClock(start);
        this.defects = defects.isEmpty() ? EnumSet.noneOf(Defect.class) : EnumSet.copyOf(defects);
        this.lastGeneration = ChronoUnit.MICROS.between(Instant.EPOCH, start);
        buckets.put(bucket, new BucketModel(bucket, location, true, !this.defects.contains(Defect.VERSIONING_DISABLED)));
        buckets.put(controlBucket, new BucketModel(controlBucket, location,
                this.defects.contains(Defect.CONTROL_BUCKET_HAS_RETENTION_LOCK), true));
        Map<Identity, Set<String>> main = new EnumMap<>(Identity.class);
        main.put(Identity.WRITER, WRITER);
        main.put(Identity.WRITER_NO_DELETE, WRITER_NO_DELETE);
        main.put(Identity.READER, READER);
        main.put(Identity.PROBE_ADMIN, this.defects.contains(Defect.PROBE_ADMIN_WITHOUT_BUCKET_GET)
                ? PROBE_ADMIN.stream().filter(p -> !p.equals("storage.buckets.get")).collect(Collectors.toSet())
                : PROBE_ADMIN);
        grants.put(bucket, main);
        grants.put(controlBucket, new EnumMap<>(Map.of(Identity.READER, READER)));
    }

    @Override
    public String evidenceClass() {
        return "offline-model";
    }

    @Override
    public GcsObjectRetentionTransport transport(Identity identity, String bucket) {
        return new ModelTransport(identity, bucket);
    }

    @Override
    public RetentionProbe probe(Identity identity) {
        return new ModelProbe(identity);
    }

    @Override
    public Clock clock() {
        return clock;
    }

    @Override
    public void sleep(Duration duration) {
        clock.advance(duration);
    }

    @Override
    public Map<String, Object> describe() {
        Map<String, Object> description = new LinkedHashMap<>();
        description.put("model", "documented Cloud Storage semantics; not a provider");
        description.put("defects", defects.stream().map(Enum::name).toList());
        return description;
    }

    private static final class BucketModel {
        final String name;
        final String location;
        final boolean objectRetention;
        final boolean versioning;
        final TreeMap<String, List<Gen>> objects = new TreeMap<>();
        final Map<String, Instant> lastWrite = new HashMap<>();
        final Map<String, Instant> lastUpdate = new HashMap<>();

        BucketModel(String name, String location, boolean objectRetention, boolean versioning) {
            this.name = name;
            this.location = location;
            this.objectRetention = objectRetention;
            this.versioning = versioning;
        }

        Optional<Gen> live(String objectName) {
            List<Gen> gens = objects.getOrDefault(objectName, List.of());
            return gens.stream().filter(gen -> !gen.deleted).reduce((first, second) -> second);
        }

        Optional<Gen> find(String objectName, long generation) {
            return objects.getOrDefault(objectName, List.of()).stream()
                    .filter(gen -> gen.generation == generation && !gen.deleted)
                    .findFirst();
        }
    }

    private static final class Gen {
        final long generation;
        final byte[] bytes;
        EvidenceRetentionMode mode;
        Instant retainUntil;
        boolean deleted;

        Gen(long generation, byte[] bytes, EvidenceRetentionMode mode, Instant retainUntil) {
            this.generation = generation;
            this.bytes = bytes;
            this.mode = mode;
            this.retainUntil = retainUntil;
        }
    }

    private BucketModel model(String name) {
        BucketModel model = buckets.get(name);
        if (model == null) {
            throw new StorageException(404, "The specified bucket does not exist.");
        }
        return model;
    }

    private void require(Identity identity, String bucket, String permission) {
        Set<String> granted = grants.getOrDefault(bucket, Map.of()).getOrDefault(identity, Set.of());
        if (!granted.contains(permission)) {
            throw new StorageException(403, identity + " does not have " + permission + " access");
        }
    }

    private boolean retained(Gen gen) {
        return gen.retainUntil != null && gen.retainUntil.isAfter(clock.instant());
    }

    private long nextGeneration() {
        long now = ChronoUnit.MICROS.between(Instant.EPOCH, clock.instant());
        if (defects.contains(Defect.GENERATIONS_DECREASE)) {
            lastGeneration = lastGeneration - 1;
        } else {
            lastGeneration = Math.max(lastGeneration + 1, now);
        }
        return lastGeneration;
    }

    private void throttle(Map<String, Instant> last, String objectName) {
        Instant previous = last.get(objectName);
        if (previous != null && Duration.between(previous, clock.instant()).compareTo(Duration.ofSeconds(1)) < 0) {
            throw new StorageException(429, "The object exceeded the rate limit for object mutation operations");
        }
        last.put(objectName, clock.instant());
    }

    private final class ModelTransport implements GcsObjectRetentionTransport {

        private final Identity identity;
        private final String bucket;

        ModelTransport(Identity identity, String bucket) {
            this.identity = identity;
            this.bucket = bucket;
        }

        @Override
        public String bucket() {
            return bucket;
        }

        @Override
        public void requireObjectRetentionEnabled() {
            synchronized (OfflineGcsAcceptanceProvider.this) {
                BucketModel model = model(bucket);
                require(identity, bucket, "storage.buckets.get");
                if (!model.objectRetention) {
                    throw new IllegalStateException("bucket " + bucket + " does not have Object Retention Lock enabled");
                }
                if (!model.versioning) {
                    throw new IllegalStateException("bucket " + bucket + " does not have Object Versioning enabled");
                }
            }
        }

        @Override
        public long createGeneration(String objectName, byte[] bytes, EvidenceRetentionMode mode, Instant retainUntil) {
            synchronized (OfflineGcsAcceptanceProvider.this) {
                BucketModel model = model(bucket);
                boolean readerMayCreate = identity == Identity.READER && defects.contains(Defect.READER_MAY_CREATE);
                if (!readerMayCreate) {
                    require(identity, bucket, "storage.objects.create");
                    require(identity, bucket, "storage.objects.setRetention");
                }
                Optional<Gen> live = model.live(objectName);
                if (live.isPresent() && !readerMayCreate) {
                    require(identity, bucket, "storage.objects.delete");
                }
                if (live.isPresent() && !model.versioning && retained(live.get())) {
                    throw new StorageException(403, "Object is under retention and cannot be replaced");
                }
                if (!retainUntil.isAfter(clock.instant())) {
                    throw new StorageException(400, "retainUntilTime must be in the future");
                }
                throttle(model.lastWrite, objectName);
                Gen gen = new Gen(nextGeneration(), bytes.clone(), mode, retainUntil);
                if (live.isPresent() && !model.versioning) {
                    live.get().deleted = true;
                }
                model.objects.computeIfAbsent(objectName, ignored -> new ArrayList<>()).add(gen);
                return gen.generation;
            }
        }

        @Override
        public RetentionState retention(String objectName, long generation) {
            synchronized (OfflineGcsAcceptanceProvider.this) {
                BucketModel model = model(bucket);
                require(identity, bucket, "storage.objects.get");
                Gen gen = model.find(objectName, generation)
                        .orElseThrow(() -> new StorageException(404, "No such object"));
                if (gen.mode == null || gen.retainUntil == null) {
                    throw new IllegalStateException("missing object retention on " + objectName + "#" + generation);
                }
                return new RetentionState(gen.mode, gen.retainUntil);
            }
        }

        @Override
        public ListPage listGenerations(String prefix, Optional<String> pageToken, int pageSize) {
            synchronized (OfflineGcsAcceptanceProvider.this) {
                BucketModel model = model(bucket);
                require(identity, bucket, "storage.objects.list");
                List<ListedGeneration> flat = new ArrayList<>();
                model.objects.tailMap(prefix, true).forEach((name, gens) -> {
                    if (name.startsWith(prefix)) {
                        gens.stream().filter(gen -> !gen.deleted)
                                .forEach(gen -> flat.add(new ListedGeneration(name, gen.generation, false)));
                    }
                });
                int start = pageToken.map(Integer::parseInt).orElse(0);
                int end = Math.min(start + pageSize, flat.size());
                List<ListedGeneration> page = new ArrayList<>(flat.subList(Math.min(start, end), end));
                boolean more = end < flat.size();
                if (more && defects.contains(Defect.LIST_DROPS_PAGE_TAIL) && !page.isEmpty()) {
                    page.remove(page.size() - 1);
                }
                return new ListPage(List.copyOf(page), more ? Optional.of(Integer.toString(end)) : Optional.empty());
            }
        }

        @Override
        public byte[] readGeneration(String objectName, long generation) {
            synchronized (OfflineGcsAcceptanceProvider.this) {
                BucketModel model = model(bucket);
                require(identity, bucket, "storage.objects.get");
                return model.find(objectName, generation)
                        .orElseThrow(() -> new StorageException(404, "No such object"))
                        .bytes.clone();
            }
        }
    }

    private final class ModelProbe implements RetentionProbe {

        private final Identity identity;

        ModelProbe(Identity identity) {
            this.identity = identity;
        }

        @Override
        public BucketSnapshot bucket(String bucket) {
            synchronized (OfflineGcsAcceptanceProvider.this) {
                BucketModel model = model(bucket);
                require(identity, bucket, "storage.buckets.get");
                return new BucketSnapshot(model.name, PROJECT_NUMBER, model.location.toUpperCase(java.util.Locale.ROOT),
                        "region", "STANDARD", model.versioning, model.objectRetention ? "Enabled" : null, null, 0L,
                        true, "enforced", false, LABELS, "2026-10-22T00:00:00Z", 1L);
            }
        }

        @Override
        public GenerationState state(String bucket, String name, long generation) {
            synchronized (OfflineGcsAcceptanceProvider.this) {
                BucketModel model = model(bucket);
                require(identity, bucket, "storage.objects.get");
                return model.find(name, generation)
                        .map(gen -> new GenerationState(true, gen.mode, gen.retainUntil))
                        .orElse(GenerationState.absent());
            }
        }

        @Override
        public ProbeOutcome setRetention(String bucket, String name, long liveGeneration, EvidenceRetentionMode mode,
                                         Instant retainUntil, boolean override) {
            return mutate(bucket, name, liveGeneration, override, gen -> {
                if (!retainUntil.isAfter(clock.instant())) {
                    return refused(400, "invalid", "retainUntilTime must be in the future");
                }
                boolean reduces = gen.retainUntil != null && retainUntil.isBefore(gen.retainUntil);
                if (gen.mode == EvidenceRetentionMode.COMPLIANCE && retained(gen)
                        && (reduces || mode != EvidenceRetentionMode.COMPLIANCE)
                        && !defects.contains(Defect.LOCKED_RETENTION_SHORTENABLE)) {
                    return refused(403, "forbidden", "Locked retention cannot be reduced or unlocked");
                }
                boolean locks = gen.mode == EvidenceRetentionMode.GOVERNANCE && mode == EvidenceRetentionMode.COMPLIANCE;
                if (gen.mode == EvidenceRetentionMode.GOVERNANCE && (reduces || locks) && !override) {
                    return refused(403, "forbidden", "overrideUnlockedRetention is required");
                }
                gen.mode = mode;
                gen.retainUntil = retainUntil;
                return ProbeOutcome.ok();
            });
        }

        @Override
        public ProbeOutcome clearRetention(String bucket, String name, long liveGeneration, boolean override) {
            return mutate(bucket, name, liveGeneration, override, gen -> {
                if (gen.mode == EvidenceRetentionMode.COMPLIANCE && retained(gen)
                        && !defects.contains(Defect.LOCKED_RETENTION_SHORTENABLE)) {
                    return refused(403, "forbidden", "Locked retention cannot be removed");
                }
                if (gen.mode == EvidenceRetentionMode.GOVERNANCE && !override) {
                    return refused(403, "forbidden", "overrideUnlockedRetention is required");
                }
                gen.mode = null;
                gen.retainUntil = null;
                return ProbeOutcome.ok();
            });
        }

        @Override
        public ProbeOutcome delete(String bucket, String name, long generation) {
            synchronized (OfflineGcsAcceptanceProvider.this) {
                BucketModel model = model(bucket);
                try {
                    require(identity, bucket, "storage.objects.delete");
                } catch (StorageException ex) {
                    return refused(403, "forbidden", ex.getMessage());
                }
                Optional<Gen> gen = model.find(name, generation);
                if (gen.isEmpty()) {
                    return refused(404, "notFound", "No such object");
                }
                if (retained(gen.get()) && !defects.contains(Defect.RETAINED_DELETABLE)) {
                    return refused(403, "forbidden", "Object is under retention and cannot be deleted");
                }
                gen.get().deleted = true;
                return ProbeOutcome.ok();
            }
        }

        private ProbeOutcome mutate(String bucket, String name, long liveGeneration, boolean override,
                                    java.util.function.Function<Gen, ProbeOutcome> change) {
            synchronized (OfflineGcsAcceptanceProvider.this) {
                BucketModel model = model(bucket);
                try {
                    require(identity, bucket, "storage.objects.update");
                    require(identity, bucket, "storage.objects.setRetention");
                    if (override) {
                        require(identity, bucket, "storage.objects.overrideUnlockedRetention");
                    }
                } catch (StorageException ex) {
                    return refused(403, "forbidden", ex.getMessage());
                }
                Optional<Gen> live = model.live(name);
                if (live.isEmpty()) {
                    return refused(404, "notFound", "No such object");
                }
                if (live.get().generation != liveGeneration) {
                    return refused(412, "conditionNotMet", "Precondition Failed");
                }
                try {
                    throttle(model.lastUpdate, name);
                } catch (StorageException ex) {
                    return refused(429, "rateLimitExceeded", ex.getMessage());
                }
                return change.apply(live.get());
            }
        }

        private ProbeOutcome refused(int code, String reason, String message) {
            return new ProbeOutcome(false, code, reason, message);
        }
    }

    /** UTC clock that only moves when the harness waits. */
    static final class VirtualClock extends Clock {

        private Instant now;

        VirtualClock(Instant start) {
            this.now = start;
        }

        synchronized void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public synchronized Instant instant() {
            return now;
        }
    }
}
