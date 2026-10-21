package com.parkio.auth.infrastructure.durable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.storage.StorageException;
import com.parkio.auth.application.durable.DurableErasureEvidence;
import com.parkio.auth.application.durable.DurableErasureEvidenceVerifier;
import com.parkio.auth.application.durable.DurableErasureEvidenceVerifier.RecoveryVerdict;
import com.parkio.auth.application.durable.ErasureLedgerEntry;
import com.parkio.auth.application.durable.EvidenceBundle;
import com.parkio.auth.application.durable.EvidenceTrust;
import com.parkio.auth.application.port.CapturedErasureLedger;
import com.parkio.auth.application.port.DurableErasureCheckpoint;
import com.parkio.auth.application.port.DurableErasurePutResult;
import com.parkio.auth.application.port.DurableErasureReceipt;
import com.parkio.auth.application.port.DurableErasureRecord;
import com.parkio.auth.domain.exception.AuthErrorCode;
import com.parkio.auth.domain.exception.AuthException;
import com.parkio.auth.infrastructure.durable.DurableEvidenceBucket.RetentionLock;
import com.parkio.auth.infrastructure.durable.DurableEvidenceBucket.StoredVersion;
import com.parkio.auth.infrastructure.durable.GcsAcceptanceLedger.CaseResult;
import com.parkio.auth.infrastructure.durable.GcsAcceptanceLedger.CreatedGeneration;
import com.parkio.auth.infrastructure.durable.GcsAcceptanceLedger.Failure;
import com.parkio.auth.infrastructure.durable.GcsAcceptanceLedger.Kind;
import com.parkio.auth.infrastructure.durable.GcsAcceptanceLedger.Status;
import com.parkio.auth.infrastructure.durable.GcsAcceptanceProvider.BucketSnapshot;
import com.parkio.auth.infrastructure.durable.GcsAcceptanceProvider.GenerationState;
import com.parkio.auth.infrastructure.durable.GcsAcceptanceProvider.Identity;
import com.parkio.auth.infrastructure.durable.GcsAcceptanceProvider.ProbeOutcome;
import com.parkio.auth.infrastructure.durable.GcsAcceptanceProvider.RetentionProbe;
import com.parkio.auth.infrastructure.durable.GcsObjectRetentionTransport.ListPage;
import java.io.IOException;
import java.io.PrintStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The #483 real-provider acceptance cases, in the order the approved operation runs them. Writes
 * and reads go through the merged {@link GcsObjectRetentionBucket} and
 * {@link ObjectLockDurableErasureRecordStore}; only the refusal and control probes use raw provider
 * calls, because the adapter has no operation that shortens, clears or deletes. Nothing is written
 * unless the preflight (F5) confirms the approved bucket configuration.
 */
final class GcsAcceptanceCases {

    static final Duration GOVERNANCE_RETENTION = Duration.ofHours(2);
    static final Duration COMPLIANCE_RETENTION = Duration.ofHours(2);
    static final int HISTORY_GENERATIONS = 205;
    static final int TRANSPORT_PAGE_SIZE = 32;
    static final int RACE_WRITERS = 8;
    /** Cloud Storage allows about one write and one metadata update per second per object name. */
    static final Duration SAME_OBJECT_SPACING = Duration.ofMillis(1_100);
    static final Duration THROTTLE_BACKOFF = Duration.ofSeconds(2);
    static final int MAX_ATTEMPTS = 4;
    static final int UNPACED_CHECKPOINT_ATTEMPTS = 2;
    static final Map<String, String> REQUIRED_LABELS = Map.of(
            "parkio-purpose", "n02-gcs-acceptance", "parkio-data", "synthetic");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<Integer> RETRYABLE_STORE_FAILURES = Set.of(429, 500, 502, 503, 504);
    private static final Duration SHORTEN_BY = Duration.ofMinutes(30);
    private static final String PATCH = "objects.patch";
    private static final String DELETE = "objects.delete";
    private static final Duration EXTEND_BY = Duration.ofSeconds(60);

    /** Bucket names, the approved location and this run's id (its namespace is {@code runs/<id>/}). */
    record Settings(String bucket, String controlBucket, String location, String runId) {

        String prefix() {
            return "runs/" + runId + "/";
        }
    }

    /** A generation a case wrote, with its body and requested retain-until. */
    private record Gen(String name, long generation, byte[] body, Instant retainUntil) {
    }

    private record Attempted<T>(T value, int attempts) {
    }

    /** An acceptance assertion that did not hold. */
    static final class CaseFailure extends RuntimeException {
        CaseFailure(String message) {
            super(message);
        }
    }

    private static final class Skip extends RuntimeException {
        Skip(String message) {
            super(message);
        }
    }

    @FunctionalInterface
    interface CaseBody {
        void run(Map<String, Object> evidence) throws Exception;
    }

    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
    }

    private final GcsAcceptanceProvider provider;
    private final GcsAcceptanceLedger ledger;
    private final Settings settings;
    private final EvidenceTrust trust;
    private final String signingKeyId;
    private final PrintStream progress;
    private final Instant t0;

    private Gen governanceBasic;
    private Gen complianceFirst;
    private Gen complianceLive;
    private Gen governanceControl;
    private List<Long> history;
    private InstrumentedGcsTransport storeTransport;
    private ObjectLockDurableErasureRecordStore store;
    private DurableErasureRecord recordOne;
    private String recordOneVersion;
    private Long checkpointSequence;
    private int storeRecords;
    private int storeRetries;
    private Map<String, Object> liveVerdict;
    private byte[] bundle;

    /** {@code progress} receives one line per finished case; null keeps the run quiet. */
    GcsAcceptanceCases(GcsAcceptanceProvider provider, GcsAcceptanceLedger ledger, Settings settings,
                       EvidenceTrust trust, String signingKeyId, PrintStream progress) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.trust = Objects.requireNonNull(trust, "trust");
        this.signingKeyId = Objects.requireNonNull(signingKeyId, "signingKeyId");
        this.progress = progress;
        this.t0 = provider.clock().instant().truncatedTo(ChronoUnit.SECONDS);
    }

    /** The exported synthetic evidence bundle (V2), if the run got that far. */
    byte[] bundle() {
        return bundle == null ? null : bundle.clone();
    }

    void runAll() {
        preflight();
        feature();
        putAndInspect();
        history();
        concurrency();
        store();
        verify();
        leastPrivilege();
        lockedRefusal();
        governanceControl();
    }

    // ---- F: preflight and feature discovery ------------------------------------------------------

    private void preflight() {
        run("F5", "Approved bucket configuration (preflight; nothing is written unless it passes)",
                Kind.PREFLIGHT, Identity.READER, ev -> {
                    BucketSnapshot main = snapshot(settings.bucket());
                    BucketSnapshot control = snapshot(settings.controlBucket());
                    ev.put("bucket", describe(main));
                    ev.put("controlBucket", describe(control));
                    List<String> problems = new ArrayList<>();
                    common(problems, "bucket", main);
                    common(problems, "controlBucket", control);
                    if (!"Enabled".equals(main.objectRetentionMode())) {
                        problems.add("bucket: Object Retention Lock is not enabled");
                    }
                    if ("Enabled".equals(control.objectRetentionMode())) {
                        problems.add("controlBucket: Object Retention Lock must stay disabled");
                    }
                    if (!problems.isEmpty()) {
                        throw new CaseFailure(String.join("; ", problems));
                    }
                });
        if (status("F5") != Status.PASS) {
            ledger.abort("preflight F5 did not pass; nothing was written");
        }
    }

    private void common(List<String> problems, String label, BucketSnapshot snapshot) {
        if (!Boolean.TRUE.equals(snapshot.versioning())) {
            problems.add(label + ": Object Versioning is not enabled");
        }
        if (snapshot.retentionPolicySeconds() != null && snapshot.retentionPolicySeconds() > 0) {
            problems.add(label + ": has a bucket retention policy (Bucket Lock is out of scope)");
        }
        if (snapshot.softDeleteSeconds() != null && snapshot.softDeleteSeconds() > 0) {
            problems.add(label + ": soft delete is enabled");
        }
        if (!Boolean.TRUE.equals(snapshot.uniformBucketLevelAccess())) {
            problems.add(label + ": uniform bucket-level access is off");
        }
        if (!"enforced".equalsIgnoreCase(String.valueOf(snapshot.publicAccessPrevention()))) {
            problems.add(label + ": public access prevention is not enforced");
        }
        if (Boolean.TRUE.equals(snapshot.hierarchicalNamespace())) {
            problems.add(label + ": hierarchical namespace is enabled");
        }
        if (!settings.location().equalsIgnoreCase(String.valueOf(snapshot.location()))) {
            problems.add(label + ": location " + snapshot.location() + " is not " + settings.location());
        }
        if (!"STANDARD".equalsIgnoreCase(String.valueOf(snapshot.storageClass()))) {
            problems.add(label + ": default storage class is not STANDARD");
        }
        REQUIRED_LABELS.forEach((key, value) -> {
            if (snapshot.labels() == null || !value.equals(snapshot.labels().get(key))) {
                problems.add(label + ": label " + key + "=" + value + " is missing");
            }
        });
    }

    private void feature() {
        run("F1", "Feature discovery passes for the read-only recovery identity", Kind.ASSERTION, Identity.READER,
                ev -> adapter(Identity.READER).requireRetentionFeature());
        run("F2", "Feature discovery passes for the writer identity", Kind.ASSERTION, Identity.WRITER,
                ev -> adapter(Identity.WRITER).requireRetentionFeature());
        run("F3", "Feature discovery refuses a bucket without Object Retention Lock", Kind.ASSERTION, Identity.READER,
                ev -> {
                    Failure cause = expectUnavailable(
                            () -> adapter(Identity.READER, settings.controlBucket()).requireRetentionFeature());
                    ev.put("cause", failure(cause));
                    if (cause == null || cause.code() != 0 || !cause.exception().startsWith("IllegalStateException")) {
                        throw new CaseFailure("refused for another reason than the missing feature");
                    }
                });
        run("F4", "Feature discovery fails closed on an absent bucket", Kind.ASSERTION, Identity.READER, ev -> {
            String absent = settings.bucket() + "-absent";
            Failure cause = expectUnavailable(() -> adapter(Identity.READER, absent).requireRetentionFeature());
            ev.put("bucket", absent);
            ev.put("cause", failure(cause));
            if (cause == null || (cause.code() != 404 && cause.code() != 403)) {
                throw new CaseFailure("expected the provider to answer 404 or 403");
            }
        });
        run("F6", "The probe identities authenticate and read the bucket before anything is written",
                Kind.PREFLIGHT, Identity.PROBE_ADMIN, ev -> {
                    adapter(Identity.WRITER_NO_DELETE).requireRetentionFeature();
                    ledger.beforeOperation("buckets.get");
                    try {
                        ev.put("probeAdminSees", provider.probe(Identity.PROBE_ADMIN).bucket(settings.bucket()).name());
                        ledger.afterOperation("buckets.get", Identity.PROBE_ADMIN, null);
                    } catch (RuntimeException ex) {
                        ledger.afterOperation("buckets.get", Identity.PROBE_ADMIN, ex);
                        throw ex;
                    }
                });
        for (String id : List.of("F1", "F2", "F6")) {
            if (status(id) != Status.PASS) {
                ledger.abort(id + " did not pass; an approved identity cannot use the bucket, nothing was written");
                return;
            }
        }
    }

    // ---- P: put, retention inspection, generation-specific reads -------------------------------

    private void putAndInspect() {
        run("P1", "GOVERNANCE put; retention and bytes read back by generation", Kind.ASSERTION, Identity.WRITER,
                ev -> governanceBasic = putAndCheck(key("adapter/governance-basic.json"),
                        EvidenceRetentionMode.GOVERNANCE, GOVERNANCE_RETENTION, "P1", ev));
        run("P2", "COMPLIANCE put; retention and bytes read back by generation", Kind.ASSERTION, Identity.WRITER,
                ev -> complianceFirst = putAndCheck(key("adapter/compliance-basic.json"),
                        EvidenceRetentionMode.COMPLIANCE, COMPLIANCE_RETENTION, "P2", ev));
        run("P3", "Overwriting a Locked live object adds a generation; the first stays canonical and Locked",
                Kind.ASSERTION, Identity.WRITER, ev -> {
                    Gen first = require(complianceFirst, "P2");
                    provider.sleep(SAME_OBJECT_SPACING);
                    GcsObjectRetentionBucket writer = adapter(Identity.WRITER);
                    byte[] body = body("P3", Map.of());
                    ZonedDateTime until = until(COMPLIANCE_RETENTION);
                    long second = generation(putWithBackoff(writer, first.name(), body,
                            EvidenceRetentionMode.COMPLIANCE, until, ev));
                    ev.put("noncurrentGeneration", Long.toString(first.generation()));
                    ev.put("liveGeneration", Long.toString(second));
                    if (second <= first.generation()) {
                        throw new CaseFailure("the second generation is not above the first");
                    }
                    if (writer.versionCount(first.name()) != 2) {
                        throw new CaseFailure("expected exactly two generations");
                    }
                    expectCanonical(writer, first.name(), first.generation(), first.body());
                    expectLock(writer.retention(first.name(), Long.toString(first.generation())),
                            EvidenceRetentionMode.COMPLIANCE, first.retainUntil(), ev, "noncurrentLock");
                    expectLock(writer.retention(first.name(), Long.toString(second)),
                            EvidenceRetentionMode.COMPLIANCE, until.toInstant(), ev, "liveLock");
                    complianceLive = new Gen(first.name(), second, body, until.toInstant());
                });
    }

    private Gen putAndCheck(String key, EvidenceRetentionMode mode, Duration retention, String caseId,
                            Map<String, Object> ev) throws InterruptedException {
        GcsObjectRetentionBucket writer = adapter(Identity.WRITER);
        byte[] body = body(caseId, Map.of());
        ZonedDateTime until = until(retention);
        long generation = generation(putWithBackoff(writer, key, body, mode, until, ev));
        ev.put("generation", Long.toString(generation));
        expectLock(writer.retention(key, Long.toString(generation)), mode, until.toInstant(), ev, "lock");
        expectCanonical(adapter(Identity.READER), key, generation, body);
        return new Gen(key, generation, body, until.toInstant());
    }

    // ---- H: multi-page historical generation enumeration ----------------------------------------

    private void history() {
        String key = key("adapter/history.json");
        run("H1", HISTORY_GENERATIONS + " sequential generations of one object, paced to the per-object write limit",
                Kind.ASSERTION, Identity.WRITER, ev -> {
                    GcsObjectRetentionBucket writer = adapter(Identity.WRITER);
                    ZonedDateTime until = until(GOVERNANCE_RETENTION);
                    List<Long> generations = new ArrayList<>();
                    for (int i = 0; i < HISTORY_GENERATIONS; i++) {
                        if (i > 0) {
                            provider.sleep(SAME_OBJECT_SPACING);
                        }
                        long generation = generation(putWithBackoff(writer, key, body("H1", Map.of("i", i)),
                                EvidenceRetentionMode.GOVERNANCE, until, ev));
                        if (!generations.isEmpty() && generation <= generations.get(generations.size() - 1)) {
                            throw new CaseFailure("write " + i + " got a generation that is not above the previous one");
                        }
                        generations.add(generation);
                    }
                    history = List.copyOf(generations);
                    ev.put("generations", generations.size());
                    ev.put("firstGeneration", Long.toString(generations.get(0)));
                    ev.put("lastGeneration", Long.toString(generations.get(generations.size() - 1)));
                });
        run("H2", "The adapter enumerates every historical generation across pages; the oldest is canonical",
                Kind.ASSERTION, Identity.READER, ev -> {
                    List<Long> written = require(history, "H1");
                    InstrumentedGcsTransport transport = transport(Identity.READER, settings.bucket());
                    GcsObjectRetentionBucket reader = new GcsObjectRetentionBucket(transport);
                    int count = reader.versionCount(key);
                    Optional<StoredVersion> oldest = reader.oldest(key);
                    List<StoredVersion> all = reader.allVersions(key);
                    List<Integer> pages = enumerations(transport.pages(), key);
                    ev.put("versionCount", count);
                    ev.put("listingOrder", order(all));
                    ev.put("pageSize", GcsObjectRetentionBucket.LIST_PAGE_SIZE);
                    ev.put("pagesPerEnumeration", pages);
                    if (count != written.size() || all.size() != written.size()) {
                        throw new CaseFailure("enumerated " + count + "/" + all.size() + " of " + written.size()
                                + " generations");
                    }
                    if (oldest.isEmpty() || !oldest.get().versionId().equals(Long.toString(written.get(0)))
                            || index(oldest.get().bytes()) != 0) {
                        throw new CaseFailure("the canonical version is not the first write");
                    }
                    for (StoredVersion version : all) {
                        int i = index(version.bytes());
                        if (i < 0 || i >= written.size() || !Long.toString(written.get(i)).equals(version.versionId())) {
                            throw new CaseFailure("generation " + version.versionId() + " does not hold write " + i);
                        }
                    }
                    if (pages.size() < 3 || pages.stream().anyMatch(p -> p < 3)) {
                        throw new CaseFailure("an enumeration of " + written.size() + " generations used fewer than 3 pages");
                    }
                });
        run("H3", "Transport pagination at " + TRANSPORT_PAGE_SIZE + " per page returns the same generations once",
                Kind.ASSERTION, Identity.READER, ev -> {
                    List<Long> written = require(history, "H1");
                    InstrumentedGcsTransport transport = transport(Identity.READER, settings.bucket());
                    List<Long> seen = new ArrayList<>();
                    Optional<String> token = Optional.empty();
                    int pages = 0;
                    do {
                        ListPage page = transport.listGenerations(key, token, TRANSPORT_PAGE_SIZE);
                        if (++pages > HISTORY_GENERATIONS + 1) {
                            throw new CaseFailure("pagination did not terminate");
                        }
                        page.items().stream().filter(item -> item.objectName().equals(key))
                                .forEach(item -> seen.add(item.generation()));
                        token = page.nextPageToken();
                    } while (token.isPresent());
                    List<String> keys = adapter(Identity.READER).keys(key("adapter/"));
                    ev.put("pages", pages);
                    ev.put("keysUnderRunAdapterPrefix", keys.size());
                    if (new HashSet<>(seen).size() != seen.size()) {
                        throw new CaseFailure("a generation was listed twice");
                    }
                    if (!new HashSet<>(seen).equals(new HashSet<>(written))) {
                        throw new CaseFailure("the paged listing differs from the written generations");
                    }
                    if (pages < (written.size() + TRANSPORT_PAGE_SIZE - 1) / TRANSPORT_PAGE_SIZE) {
                        throw new CaseFailure("the provider returned more items per page than requested");
                    }
                    for (Gen expected : new Gen[] {governanceBasic, complianceFirst}) {
                        if (expected != null && !keys.contains(expected.name())) {
                            throw new CaseFailure("prefix listing misses " + expected.name());
                        }
                    }
                    if (!keys.contains(key)) {
                        throw new CaseFailure("prefix listing misses the history object");
                    }
                });
    }

    // ---- C: concurrent writers and retries -------------------------------------------------------

    private void concurrency() {
        run("C1", RACE_WRITERS + " concurrent writers on one object: the lowest generation is canonical with its writer's bytes",
                Kind.ASSERTION, Identity.WRITER, ev -> {
                    String key = key("adapter/race.json");
                    ZonedDateTime until = until(GOVERNANCE_RETENTION);
                    Map<Integer, byte[]> bodies = new HashMap<>();
                    List<Future<Object>> futures = new ArrayList<>();
                    CountDownLatch start = new CountDownLatch(1);
                    ExecutorService pool = Executors.newFixedThreadPool(RACE_WRITERS);
                    try {
                        for (int w = 0; w < RACE_WRITERS; w++) {
                            byte[] body = body("C1", Map.of("writer", w));
                            bodies.put(w, body);
                            GcsObjectRetentionBucket writer = adapter(Identity.WRITER);
                            futures.add(pool.submit(() -> {
                                start.await();
                                try {
                                    return Long.parseLong(writer.put(key, body, EvidenceRetentionMode.GOVERNANCE, until));
                                } catch (AuthException ex) {
                                    Failure cause = ledger.lastFailure();
                                    return cause == null ? new Failure("objects.insert", 0, "", "unknown") : cause;
                                }
                            }));
                        }
                        start.countDown();
                        Map<Long, Integer> acknowledged = new TreeMap<>();
                        Map<Integer, Failure> failed = new TreeMap<>();
                        for (int w = 0; w < futures.size(); w++) {
                            Object outcome = futures.get(w).get(2, TimeUnit.MINUTES);
                            if (outcome instanceof Long generation) {
                                acknowledged.put(generation, w);
                            } else {
                                failed.put(w, (Failure) outcome);
                            }
                        }
                        raceOutcome(key, bodies, acknowledged, failed, ev);
                    } finally {
                        pool.shutdownNow();
                    }
                });
        run("C2", "An identical body written twice is kept twice by the provider; the first stays canonical",
                Kind.ASSERTION, Identity.WRITER, ev -> {
                    String key = key("adapter/identical-retry.json");
                    byte[] body = body("C2", Map.of());
                    ZonedDateTime until = until(GOVERNANCE_RETENTION);
                    GcsObjectRetentionBucket writer = adapter(Identity.WRITER);
                    long first = generation(putWithBackoff(writer, key, body, EvidenceRetentionMode.GOVERNANCE, until, ev));
                    provider.sleep(SAME_OBJECT_SPACING);
                    long second = generation(putWithBackoff(writer, key, body, EvidenceRetentionMode.GOVERNANCE, until, ev));
                    ev.put("firstGeneration", Long.toString(first));
                    ev.put("secondGeneration", Long.toString(second));
                    if (second <= first || writer.versionCount(key) != 2) {
                        throw new CaseFailure("expected two ascending generations of the identical body");
                    }
                    expectCanonical(adapter(Identity.READER), key, first, body);
                });
    }

    private void raceOutcome(String key, Map<Integer, byte[]> bodies, Map<Long, Integer> acknowledged,
                             Map<Integer, Failure> failed, Map<String, Object> ev) {
        GcsObjectRetentionBucket reader = adapter(Identity.READER);
        List<StoredVersion> listed = reader.allVersions(key);
        Set<Long> generations = listed.stream().map(v -> Long.parseLong(v.versionId())).collect(Collectors.toSet());
        long throttled = failed.values().stream().filter(f -> f.code() == 429).count();
        long ambiguous = failed.values().stream().filter(f -> f.code() == 0 || f.code() >= 500).count();
        long unacknowledged = generations.stream().filter(g -> !acknowledged.containsKey(g)).count();
        ev.put("acknowledged", acknowledged.size());
        ev.put("throttled", throttled);
        ev.put("otherFailures", failed.values().stream().filter(f -> f.code() != 429).map(this::failure).toList());
        ev.put("listedGenerations", generations.size());
        ev.put("unacknowledgedGenerations", unacknowledged);
        if (acknowledged.isEmpty()) {
            throw new CaseFailure("no concurrent writer succeeded");
        }
        if (!generations.containsAll(acknowledged.keySet())) {
            throw new CaseFailure("an acknowledged generation is not listed");
        }
        if (unacknowledged > ambiguous) {
            throw new CaseFailure("listed generations that no writer can account for");
        }
        long lowest = Collections.min(generations);
        StoredVersion canonical = reader.oldest(key).orElseThrow(() -> new CaseFailure("no canonical version"));
        Integer owner = acknowledged.get(lowest);
        ev.put("canonicalGeneration", canonical.versionId());
        ev.put("canonicalWriter", owner);
        if (!canonical.versionId().equals(Long.toString(lowest))) {
            throw new CaseFailure("the canonical version is not the lowest listed generation");
        }
        if (owner != null && !Arrays.equals(canonical.bytes(), bodies.get(owner))) {
            throw new CaseFailure("the canonical bytes are not those of the writer that got that generation");
        }
        for (StoredVersion version : listed) {
            if (bodies.values().stream().noneMatch(body -> Arrays.equals(body, version.bytes()))) {
                throw new CaseFailure("generation " + version.versionId() + " holds bytes no writer sent");
            }
        }
    }

    // ---- S: the store protocol on the live provider ----------------------------------------------

    private void store() {
        storeTransport = transport(Identity.WRITER, settings.bucket());
        store = new ObjectLockDurableErasureRecordStore(
                new PrefixedEvidenceBucket(new GcsObjectRetentionBucket(storeTransport), storePrefix()), trust,
                signingKeyId, EvidenceRetentionMode.COMPLIANCE, COMPLIANCE_RETENTION, provider.clock());
        run("S1", "Store putIfAbsent: COMPLIANCE receipt on the canonical record generation", Kind.ASSERTION,
                Identity.WRITER, ev -> {
                    DurableErasureRecord record = record();
                    Attempted<DurableErasurePutResult> put = putRecord(record);
                    DurableErasureReceipt receipt = put.value().receipt();
                    ev.put("attempts", put.attempts());
                    ev.put("receipt", receipt(receipt));
                    ev.put("retainUntilPrecision", precision(DurableErasureEvidence.recordKey(record.erasureRequestId()), receipt));
                    if (put.value().conflict() || (!put.value().created() && put.attempts() == 1)) {
                        throw new CaseFailure("a new record was not created");
                    }
                    if (!"COMPLIANCE".equals(receipt.retentionMode())) {
                        throw new CaseFailure("the receipt lock is " + receipt.retentionMode());
                    }
                    Instant expected = provider.clock().instant().plus(COMPLIANCE_RETENTION);
                    if (Duration.between(receipt.retainUntil(), expected).abs().compareTo(Duration.ofMinutes(5)) > 0) {
                        throw new CaseFailure("the receipt retain-until is not about now + " + COMPLIANCE_RETENTION);
                    }
                    StoredVersion canonical = storeBucket(Identity.READER)
                            .oldest(DurableErasureEvidence.recordKey(record.erasureRequestId()))
                            .orElseThrow(() -> new CaseFailure("the record is not readable"));
                    if (!canonical.versionId().equals(receipt.versionId())) {
                        throw new CaseFailure("the receipt does not name the canonical generation");
                    }
                    if (!store.findByRequestId(record.erasureRequestId()).equals(Optional.of(record))) {
                        throw new CaseFailure("findByRequestId does not return the record");
                    }
                    recordOne = record;
                    recordOneVersion = receipt.versionId();
                    storeRecords++;
                });
        run("S2", "An identical retry settles on the canonical record without writing", Kind.ASSERTION,
                Identity.WRITER, ev -> {
                    DurableErasureRecord record = require(recordOne, "S1");
                    int before = ledger.created().size();
                    DurableErasurePutResult result = putRecord(record).value();
                    int written = ledger.created().size() - before;
                    ev.put("generationsWritten", written);
                    if (result.created() || result.conflict() || !result.receipt().versionId().equals(recordOneVersion)) {
                        throw new CaseFailure("the retry did not settle on the existing record");
                    }
                    if (written != 0 || storeBucket(Identity.READER).versionCount(recordKey(record)) != 1) {
                        throw new CaseFailure("the identical retry wrote to the store");
                    }
                });
        run("S3", "A different body for the same request is a conflict; the canonical record is unchanged",
                Kind.ASSERTION, Identity.WRITER, ev -> {
                    DurableErasureRecord record = require(recordOne, "S1");
                    DurableErasureRecord conflicting = DurableErasureRecord.of(record.erasureRequestId(),
                            UUID.randomUUID(), record.erasedAt().plusSeconds(1));
                    int before = ledger.created().size();
                    DurableErasurePutResult result = putRecord(conflicting).value();
                    int written = ledger.created().size() - before;
                    ev.put("generationsWritten", written);
                    if (!result.conflict() || !result.receipt().versionId().equals(recordOneVersion)) {
                        throw new CaseFailure("the conflicting body was not reported as a conflict");
                    }
                    if (written != 0 || storeBucket(Identity.READER).versionCount(recordKey(record)) != 1) {
                        throw new CaseFailure("the conflicting put wrote to the store");
                    }
                });
        run("S4", "A response lost after the provider committed the record: the retry converges on that generation",
                Kind.ASSERTION, Identity.WRITER, ev -> lostResponse(ev));
        run("S5", "Checkpoint publication as merged (unpaced): its two frontier writes against the per-object limit",
                Kind.MEASUREMENT, Identity.WRITER, ev -> {
                    int before = ledger.created().size();
                    try {
                        Attempted<DurableErasureCheckpoint> published = attempt(
                                () -> store.publishCheckpoint(() -> capture()), UNPACED_CHECKPOINT_ATTEMPTS);
                        checkpointSequence = checked(published.value()).sequence();
                        ev.put("converged", true);
                        ev.put("attempts", published.attempts());
                        ev.put("sequence", checkpointSequence);
                        ev.put("filledReservation", published.value().filledReservation());
                    } catch (AuthException ex) {
                        if (ex.errorCode() != AuthErrorCode.DURABLE_RECORDING_UNAVAILABLE) {
                            throw ex;
                        }
                        ev.put("converged", false);
                        ev.put("attempts", UNPACED_CHECKPOINT_ATTEMPTS);
                        ev.put("lastCause", failure(ledger.lastFailure()));
                    }
                    ev.put("generationsWritten", ledger.created().size() - before);
                });
        run("S5b", "Checkpoint publication completes when writes to one object are spaced by the documented limit",
                Kind.ASSERTION, Identity.WRITER, ev -> {
                    if (checkpointSequence != null) {
                        ev.put("needed", false);
                        return;
                    }
                    ev.put("needed", true);
                    storeTransport.paceSameObjectWrites(SAME_OBJECT_SPACING);
                    try {
                        Attempted<DurableErasureCheckpoint> published = attempt(
                                () -> store.publishCheckpoint(() -> capture()), MAX_ATTEMPTS);
                        checkpointSequence = checked(published.value()).sequence();
                        ev.put("attempts", published.attempts());
                        ev.put("sequence", checkpointSequence);
                        ev.put("filledReservation", published.value().filledReservation());
                    } finally {
                        storeTransport.stopPacing();
                    }
                });
        run("S6", "Further records under the provider's per-object write rate; frontier history grows",
                Kind.ASSERTION, Identity.WRITER, ev -> {
                    for (int i = 0; i < 2; i++) {
                        Attempted<DurableErasurePutResult> put = putRecord(record());
                        if (put.value().conflict() || (!put.value().created() && put.attempts() == 1)) {
                            throw new CaseFailure("record " + i + " was not created");
                        }
                        storeRecords++;
                    }
                    List<CreatedGeneration> frontier = ledger.created().stream()
                            .filter(item -> item.name().equals(storePrefix() + DurableErasureEvidence.FRONTIER_KEY))
                            .toList();
                    ev.put("frontierGenerations", frontier.size());
                    ev.put("minimumFrontierWriteIntervalMs", minimumIntervalMs(frontier));
                    ev.put("storeRetriesAfterThrottlingOrUnavailable", storeRetries);
                });
    }

    private void lostResponse(Map<String, Object> ev) throws InterruptedException {
        DurableErasureRecord record = record();
        String objectName = storePrefix() + recordKey(record);
        provider.sleep(SAME_OBJECT_SPACING);
        storeTransport.loseResponseAfterCommitOnce(objectName);
        int attempts = 0;
        boolean lost = false;
        while (!lost) {
            if (++attempts > MAX_ATTEMPTS) {
                throw new CaseFailure("the record write was never reached");
            }
            try {
                store.putIfAbsent(record);
                throw new CaseFailure("putIfAbsent succeeded although the record response was lost");
            } catch (AuthException ex) {
                if (ex.errorCode() != AuthErrorCode.DURABLE_RECORDING_UNAVAILABLE) {
                    throw ex;
                }
                // The adapter names the failure's type; anything else (a throttled write) is retried.
                lost = String.valueOf(ex.getMessage())
                        .endsWith(InstrumentedGcsTransport.SimulatedLostResponse.class.getSimpleName());
                if (!lost) {
                    storeRetries++;
                    provider.sleep(THROTTLE_BACKOFF);
                }
            }
        }
        long committed = require(storeTransport.lostResponseGeneration(), "the lost-response fault");
        boolean visibleBefore = store.findByRequestId(record.erasureRequestId()).isPresent();
        int versionsBefore = storeBucket(Identity.READER).versionCount(recordKey(record));
        RecoveryVerdict interim = verifier().recover(ObjectLockEvidenceObjects.of(storeBucket(Identity.READER)), null);
        boolean trustedBefore = interim.pending().stream()
                .anyMatch(item -> item.erasureRequestId().equals(record.erasureRequestId().toString()));
        Attempted<DurableErasurePutResult> retry = putRecord(record);
        DurableErasurePutResult result = retry.value();
        int versionsAfter = storeBucket(Identity.READER).versionCount(recordKey(record));
        boolean visibleAfter = store.findByRequestId(record.erasureRequestId()).isPresent();
        ev.put("committedGeneration", Long.toString(committed));
        ev.put("visibleBeforeRetry", visibleBefore);
        ev.put("trustedByVerifierBeforeRetry", trustedBefore);
        ev.put("interimVerdict", verdict(interim));
        ev.put("retryAttempts", retry.attempts());
        ev.put("retryCreated", result.created());
        ev.put("versionsBeforeRetry", versionsBefore);
        ev.put("versionsAfterRetry", versionsAfter);
        if (visibleBefore || trustedBefore) {
            throw new CaseFailure("the record counted as durable before the frontier covered it");
        }
        if (!interim.completenessEstablished()) {
            throw new CaseFailure("the interim state is not a consistent prefix");
        }
        if (result.conflict() || result.created() || !result.receipt().versionId().equals(Long.toString(committed))) {
            throw new CaseFailure("the retry did not settle on the committed generation");
        }
        if (versionsBefore != 1 || versionsAfter != 1) {
            throw new CaseFailure("the retry wrote a second record generation");
        }
        if (!visibleAfter) {
            throw new CaseFailure("the record is not durable after the retry");
        }
        storeRecords++;
    }

    // ---- V: the existing verifier over evidence read from the live bucket ------------------------

    private void verify() {
        run("V1", "Local verifier over the live store, read by the read-only identity: complete coverage",
                Kind.ASSERTION, Identity.READER, ev -> {
                    Long checkpoint = require(checkpointSequence, "S5/S5b");
                    PrefixedEvidenceBucket reader = storeBucket(Identity.READER);
                    long required = highestSequence(reader);
                    RecoveryVerdict verdict = verifier().recover(ObjectLockEvidenceObjects.of(reader), required);
                    liveVerdict = verdict(verdict);
                    ev.put("requiredThroughSequence", required);
                    ev.put("verdict", liveVerdict);
                    if (!verdict.completenessEstablished() || !verdict.gaps().isEmpty()
                            || !verdict.abandonedReservations().isEmpty() || verdict.ignoredFrontierVersions() != 0) {
                        throw new CaseFailure("the live evidence is not complete and clean");
                    }
                    if (verdict.expectedThrough() == null || verdict.expectedThrough() != required
                            || verdict.pending().size() != storeRecords
                            || !checkpoint.equals(verdict.latestTrustedCheckpoint())) {
                        throw new CaseFailure("the verified boundary does not match what the store published");
                    }
                });
        run("V2", "An exported bundle of the live store verifies with the same verdict", Kind.ASSERTION,
                Identity.READER, ev -> {
                    Map<String, Object> expected = require(liveVerdict, "V1");
                    PrefixedEvidenceBucket reader = storeBucket(Identity.READER);
                    ObjectLockEvidenceObjects objects = ObjectLockEvidenceObjects.of(reader);
                    Map<String, byte[]> canonical = new LinkedHashMap<>();
                    for (String evidencePrefix : List.of("records/", "sequences/", "checkpoints/")) {
                        for (String key : objects.list(evidencePrefix)) {
                            canonical.put(key, objects.find(key).orElseThrow(() -> new CaseFailure("vanished: " + key)));
                        }
                    }
                    List<EvidenceBundle.FrontierVersion> versions = reader.allVersions(DurableErasureEvidence.FRONTIER_KEY)
                            .stream().map(v -> new EvidenceBundle.FrontierVersion(v.versionId(), v.bytes())).toList();
                    byte[] encoded = EvidenceBundle.encode(canonical, versions,
                            "gcs-object-retention:" + settings.bucket() + "/" + storePrefix());
                    EvidenceBundle parsed = EvidenceBundle.parse(JSON.readTree(encoded));
                    Map<String, Object> bundled = verdict(verifier().recover(parsed, highestSequence(reader)));
                    bundle = encoded;
                    ev.put("sha256", sha256(encoded));
                    ev.put("objects", canonical.size());
                    ev.put("frontierVersions", versions.size());
                    ev.put("verdict", bundled);
                    if (!bundled.equals(expected)) {
                        throw new CaseFailure("the bundle verdict differs from the live verdict");
                    }
                });
    }

    // ---- L: least privilege -----------------------------------------------------------------------

    private void leastPrivilege() {
        run("L1", "The read-only identity cannot write; the adapter fails closed and nothing is created",
                Kind.ASSERTION, Identity.READER, ev -> {
                    String key = key("adapter/reader-denied.json");
                    Failure cause = expectUnavailable(() -> adapter(Identity.READER).put(key, body("L1", Map.of()),
                            EvidenceRetentionMode.GOVERNANCE, until(GOVERNANCE_RETENTION)));
                    ev.put("cause", failure(cause));
                    if (cause == null || cause.code() != 403) {
                        throw new CaseFailure("expected an IAM refusal (403)");
                    }
                    if (adapter(Identity.READER).versionCount(key) != 0) {
                        throw new CaseFailure("a generation exists after the refused write");
                    }
                });
        run("L2", "Writer without storage.objects.delete: first write versus versioned overwrite",
                Kind.MEASUREMENT, Identity.WRITER_NO_DELETE, ev -> {
                    String key = key("adapter/no-delete-overwrite.json");
                    GcsObjectRetentionBucket writer = adapter(Identity.WRITER_NO_DELETE);
                    ZonedDateTime until = until(GOVERNANCE_RETENTION);
                    long first = generation(putWithBackoff(writer, key, body("L2", Map.of("write", 1)),
                            EvidenceRetentionMode.GOVERNANCE, until, ev));
                    provider.sleep(SAME_OBJECT_SPACING);
                    Failure overwrite = null;
                    try {
                        putWithBackoff(writer, key, body("L2", Map.of("write", 2)), EvidenceRetentionMode.GOVERNANCE,
                                until, ev);
                    } catch (AuthException ex) {
                        if (ex.errorCode() != AuthErrorCode.DURABLE_RECORDING_UNAVAILABLE) {
                            throw ex;
                        }
                        overwrite = ledger.lastFailure();
                    }
                    int versions = adapter(Identity.READER).versionCount(key);
                    boolean refused = overwrite != null;
                    ev.put("firstGeneration", Long.toString(first));
                    ev.put("overwriteRefused", refused);
                    ev.put("overwriteCause", failure(overwrite));
                    ev.put("overwriteRequiresDelete", refused && overwrite.code() == 403);
                    ev.put("listedVersions", versions);
                    if (versions != (refused ? 1 : 2)) {
                        throw new CaseFailure("the listed versions do not match the overwrite outcome");
                    }
                });
        run("L3", "The writer cannot delete a retained GOVERNANCE generation", Kind.ASSERTION, Identity.WRITER,
                ev -> refusedUnchanged(Identity.WRITER, require(governanceBasic, "P1"), ev, DELETE,
                        (probe, gen) -> probe.delete(settings.bucket(), gen.name(), gen.generation())));
        run("L4", "The writer cannot shorten GOVERNANCE retention, even asking to override it", Kind.ASSERTION,
                Identity.WRITER, ev -> refusedUnchanged(Identity.WRITER, require(governanceBasic, "P1"), ev, PATCH,
                        (probe, gen) -> probe.setRetention(settings.bucket(), gen.name(), gen.generation(),
                                EvidenceRetentionMode.GOVERNANCE, gen.retainUntil().minus(SHORTEN_BY), true)));
    }

    // ---- K: Locked (COMPLIANCE) refusals by the object admin -------------------------------------

    private void lockedRefusal() {
        run("K1", "Locked retention cannot be shortened, even by the object admin with override", Kind.ASSERTION,
                Identity.PROBE_ADMIN, ev -> refusedUnchanged(Identity.PROBE_ADMIN, require(complianceLive, "P3"), ev,
                        PATCH, (probe, gen) -> probe.setRetention(settings.bucket(), gen.name(), gen.generation(),
                                EvidenceRetentionMode.COMPLIANCE, gen.retainUntil().minus(SHORTEN_BY), true)));
        run("K2", "Locked retention cannot be removed", Kind.ASSERTION, Identity.PROBE_ADMIN,
                ev -> refusedUnchanged(Identity.PROBE_ADMIN, require(complianceLive, "P3"), ev, PATCH,
                        (probe, gen) -> probe.clearRetention(settings.bucket(), gen.name(), gen.generation(), true)));
        run("K3", "Locked retention cannot be switched to Unlocked", Kind.ASSERTION, Identity.PROBE_ADMIN,
                ev -> refusedUnchanged(Identity.PROBE_ADMIN, require(complianceLive, "P3"), ev, PATCH,
                        (probe, gen) -> probe.setRetention(settings.bucket(), gen.name(), gen.generation(),
                                EvidenceRetentionMode.GOVERNANCE, gen.retainUntil(), true)));
        run("K4", "A Locked live generation cannot be deleted", Kind.ASSERTION, Identity.PROBE_ADMIN,
                ev -> refusedUnchanged(Identity.PROBE_ADMIN, require(complianceLive, "P3"), ev, DELETE,
                        (probe, gen) -> probe.delete(settings.bucket(), gen.name(), gen.generation())));
        run("K5", "A Locked noncurrent generation cannot be deleted", Kind.ASSERTION, Identity.PROBE_ADMIN,
                ev -> refusedUnchanged(Identity.PROBE_ADMIN, require(complianceFirst, "P2"), ev, DELETE,
                        (probe, gen) -> probe.delete(settings.bucket(), gen.name(), gen.generation())));
        run("K6", "Positive control: Locked retention can be extended (by 60 s)", Kind.ASSERTION,
                Identity.PROBE_ADMIN, ev -> {
                    Gen live = require(complianceLive, "P3");
                    Instant extended = live.retainUntil().plus(EXTEND_BY);
                    ProbeOutcome outcome = mutate(Identity.PROBE_ADMIN, PATCH, probe -> probe.setRetention(
                            settings.bucket(), live.name(), live.generation(), EvidenceRetentionMode.COMPLIANCE,
                            extended, false));
                    ev.put("outcome", outcome(outcome));
                    if (!outcome.accepted()) {
                        throw new CaseFailure("the extension was refused");
                    }
                    ledger.extendRetainUntil(live.name(), live.generation(), extended);
                    GenerationState after = state(live);
                    ev.put("after", state(after));
                    if (after.mode() != EvidenceRetentionMode.COMPLIANCE || !extended.equals(after.retainUntil())) {
                        throw new CaseFailure("the extension is not visible");
                    }
                });
        run("K7", "A record the store published under COMPLIANCE cannot be deleted", Kind.ASSERTION,
                Identity.PROBE_ADMIN, ev -> {
                    String version = require(recordOneVersion, "S1");
                    Gen record = new Gen(storePrefix() + recordKey(recordOne), Long.parseLong(version), null, null);
                    refusedUnchanged(Identity.PROBE_ADMIN, record, ev, DELETE,
                            (probe, gen) -> probe.delete(settings.bucket(), gen.name(), gen.generation()));
                });
    }

    // ---- G: GOVERNANCE (Unlocked) control ---------------------------------------------------------

    private void governanceControl() {
        run("G1", "A retained GOVERNANCE generation cannot be deleted, even by the object admin", Kind.ASSERTION,
                Identity.PROBE_ADMIN, ev -> {
                    String key = key("adapter/governance-control.json");
                    byte[] body = body("G1", Map.of());
                    ZonedDateTime until = until(GOVERNANCE_RETENTION);
                    long generation = generation(putWithBackoff(adapter(Identity.WRITER), key, body,
                            EvidenceRetentionMode.GOVERNANCE, until, ev));
                    governanceControl = new Gen(key, generation, body, until.toInstant());
                    refusedUnchanged(Identity.PROBE_ADMIN, governanceControl, ev, DELETE,
                            (probe, gen) -> probe.delete(settings.bucket(), gen.name(), gen.generation()));
                });
        run("G2", "Shortening GOVERNANCE retention without overrideUnlockedRetention is refused", Kind.ASSERTION,
                Identity.PROBE_ADMIN, ev -> refusedUnchanged(Identity.PROBE_ADMIN, require(governanceControl, "G1"), ev,
                        PATCH, (probe, gen) -> probe.setRetention(settings.bucket(), gen.name(), gen.generation(),
                                EvidenceRetentionMode.GOVERNANCE, gen.retainUntil().minus(SHORTEN_BY), false)));
        run("G3", "With overrideUnlockedRetention the object admin can shorten GOVERNANCE retention",
                Kind.ASSERTION, Identity.PROBE_ADMIN, ev -> {
                    Gen gen = require(governanceControl, "G1");
                    Instant shorter = gen.retainUntil().minus(SHORTEN_BY);
                    ProbeOutcome outcome = mutate(Identity.PROBE_ADMIN, PATCH, probe -> probe.setRetention(
                            settings.bucket(), gen.name(), gen.generation(), EvidenceRetentionMode.GOVERNANCE, shorter,
                            true));
                    ev.put("outcome", outcome(outcome));
                    GenerationState after = state(gen);
                    ev.put("after", state(after));
                    if (!outcome.accepted() || !shorter.equals(after.retainUntil())) {
                        throw new CaseFailure("the overriding shortening did not take effect");
                    }
                });
        run("G4", "With override the GOVERNANCE retention is removed and the generation can be deleted",
                Kind.ASSERTION, Identity.PROBE_ADMIN, ev -> {
                    Gen gen = require(governanceControl, "G1");
                    ProbeOutcome cleared = mutate(Identity.PROBE_ADMIN, PATCH,
                            probe -> probe.clearRetention(settings.bucket(), gen.name(), gen.generation(), true));
                    ev.put("clear", outcome(cleared));
                    GenerationState unlocked = state(gen);
                    if (!cleared.accepted() || unlocked.mode() != null) {
                        throw new CaseFailure("the retention was not removed");
                    }
                    ProbeOutcome deleted = probeCall(Identity.PROBE_ADMIN, DELETE,
                            probe -> probe.delete(settings.bucket(), gen.name(), gen.generation()));
                    ev.put("delete", outcome(deleted));
                    if (!deleted.accepted() || state(gen).exists()) {
                        throw new CaseFailure("the unretained generation was not deleted");
                    }
                    ledger.deletedGeneration(gen.name(), gen.generation());
                });
    }

    // ---- case plumbing ------------------------------------------------------------------------------

    private void run(String id, String title, Kind kind, Identity identity, CaseBody body) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        Instant started = provider.clock().instant();
        if (ledger.aborted()) {
            ledger.record(new CaseResult(id, title, kind, identity, Status.SKIPPED,
                    "run stopped: " + ledger.abortReason(), evidence, started, 0, Map.of()));
            if (progress != null) {
                progress.printf("  %-4s %-8s %7d ms  %s%n", id, Status.SKIPPED, 0, title);
            }
            return;
        }
        ledger.startCase(id);
        Map<String, Long> before = ledger.counters();
        Status status;
        String detail = "";
        try {
            body.run(evidence);
            status = kind == Kind.MEASUREMENT ? Status.MEASURED : Status.PASS;
        } catch (CaseFailure ex) {
            status = Status.FAIL;
            detail = ex.getMessage();
        } catch (Skip ex) {
            status = Status.SKIPPED;
            detail = ex.getMessage();
        } catch (GcsAcceptanceLedger.BudgetExceeded ex) {
            status = Status.ERROR;
            detail = "stopped: " + ex.getMessage();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            ledger.abort("interrupted");
            status = Status.ERROR;
            detail = "interrupted";
        } catch (Exception ex) {
            status = Status.ERROR;
            detail = ex.getClass().getSimpleName() + ": " + ex.getMessage();
        }
        if (status == Status.FAIL && ledger.aborted()) {
            status = Status.ERROR;
            detail = "stopped: " + ledger.abortReason();
        }
        Map<String, Long> after = ledger.counters();
        Map<String, Long> delta = new LinkedHashMap<>();
        after.forEach((key, value) -> delta.put(key, value - before.getOrDefault(key, 0L)));
        long durationMs = Duration.between(started, provider.clock().instant()).toMillis();
        String clean = GcsAcceptanceLedger.sanitize(detail);
        ledger.record(new CaseResult(id, title, kind, identity, status, clean, evidence, started, durationMs, delta));
        if (progress != null) {
            progress.printf("  %-4s %-8s %7d ms  %s%s%n", id, status, durationMs, title,
                    clean.isEmpty() ? "" : " :: " + clean);
        }
    }

    private Status status(String id) {
        return ledger.cases().stream().filter(result -> result.id().equals(id)).map(CaseResult::status)
                .findFirst().orElse(Status.SKIPPED);
    }

    private static <T> T require(T value, String producingCase) {
        if (value == null) {
            throw new Skip("needs the result of " + producingCase);
        }
        return value;
    }

    private GcsObjectRetentionBucket adapter(Identity identity) {
        return adapter(identity, settings.bucket());
    }

    private GcsObjectRetentionBucket adapter(Identity identity, String bucket) {
        return new GcsObjectRetentionBucket(transport(identity, bucket));
    }

    private InstrumentedGcsTransport transport(Identity identity, String bucket) {
        return new InstrumentedGcsTransport(provider.transport(identity, bucket), identity, ledger, provider);
    }

    private PrefixedEvidenceBucket storeBucket(Identity identity) {
        return new PrefixedEvidenceBucket(adapter(identity), storePrefix());
    }

    private String storePrefix() {
        return settings.prefix() + "store/";
    }

    private String key(String suffix) {
        return settings.prefix() + suffix;
    }

    private static String recordKey(DurableErasureRecord record) {
        return DurableErasureEvidence.recordKey(record.erasureRequestId());
    }

    private ZonedDateTime until(Duration retention) {
        return t0.plus(retention).atZone(ZoneOffset.UTC);
    }

    private DurableErasureRecord record() {
        return DurableErasureRecord.of(UUID.randomUUID(), UUID.randomUUID(),
                provider.clock().instant().truncatedTo(ChronoUnit.MICROS));
    }

    private DurableErasureEvidenceVerifier verifier() {
        return new DurableErasureEvidenceVerifier(trust, provider.clock().instant());
    }

    /** Synthetic JSON: run id, case id, a nonce and the case's fields. Never real evidence. */
    private byte[] body(String caseId, Map<String, Object> fields) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("format", "parkio-n02-acceptance-synthetic");
        body.put("run", settings.runId());
        body.put("case", caseId);
        body.put("nonce", UUID.randomUUID().toString());
        body.putAll(fields);
        try {
            return JSON.writeValueAsBytes(body);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static int index(byte[] body) {
        try {
            return JSON.readTree(body).path("i").asInt(-1);
        } catch (IOException ex) {
            return -1;
        }
    }

    private static long generation(String versionId) {
        return Long.parseLong(versionId);
    }

    /** One adapter put; a 429 (no write happened) is retried after a backoff, anything else is not. */
    private String putWithBackoff(GcsObjectRetentionBucket bucket, String key, byte[] body, EvidenceRetentionMode mode,
                                  ZonedDateTime until, Map<String, Object> ev) throws InterruptedException {
        for (int attempt = 1; ; attempt++) {
            ledger.clearLastFailure();
            try {
                return bucket.put(key, body, mode, until);
            } catch (AuthException ex) {
                Failure cause = ledger.lastFailure();
                if (attempt < MAX_ATTEMPTS && cause != null && cause.code() == 429) {
                    ev.merge("throttledAttempts", 1, (a, b) -> (Integer) a + (Integer) b);
                    provider.sleep(THROTTLE_BACKOFF);
                    continue;
                }
                throw ex;
            }
        }
    }

    private Attempted<DurableErasurePutResult> putRecord(DurableErasureRecord record) throws InterruptedException {
        return attempt(() -> store.putIfAbsent(record), MAX_ATTEMPTS);
    }

    /** Two synthetic tombstone entries, captured now (microsecond precision like the ledger). */
    private CapturedErasureLedger capture() {
        Instant now = provider.clock().instant().truncatedTo(ChronoUnit.MICROS);
        return new CapturedErasureLedger(List.of(
                new ErasureLedgerEntry(UUID.randomUUID(), now.minusSeconds(120)),
                new ErasureLedgerEntry(UUID.randomUUID(), now.minusSeconds(60))), now);
    }

    private static DurableErasureCheckpoint checked(DurableErasureCheckpoint checkpoint) {
        if (checkpoint.entryCount() != 2) {
            throw new CaseFailure("the checkpoint does not hold the two captured entries");
        }
        return checkpoint;
    }

    /** The store's own retry contract: an unavailable store is retried with the same input. */
    private <T> Attempted<T> attempt(Supplier<T> call, int maxAttempts) throws InterruptedException {
        for (int attempt = 1; ; attempt++) {
            ledger.clearLastFailure();
            try {
                return new Attempted<>(call.get(), attempt);
            } catch (AuthException ex) {
                Failure cause = ledger.lastFailure();
                boolean retryable = ex.errorCode() == AuthErrorCode.DURABLE_RECORDING_UNAVAILABLE && cause != null
                        && RETRYABLE_STORE_FAILURES.contains(cause.code());
                if (!retryable || attempt >= maxAttempts) {
                    throw ex;
                }
                storeRetries++;
                provider.sleep(THROTTLE_BACKOFF);
            }
        }
    }

    private Failure expectUnavailable(Action action) throws Exception {
        ledger.clearLastFailure();
        try {
            action.run();
        } catch (AuthException ex) {
            if (ex.errorCode() != AuthErrorCode.DURABLE_RECORDING_UNAVAILABLE) {
                throw new CaseFailure("unexpected error code " + ex.errorCode());
            }
            return ledger.lastFailure();
        }
        throw new CaseFailure("the operation succeeded");
    }

    private void expectLock(RetentionLock lock, EvidenceRetentionMode mode, Instant requested, Map<String, Object> ev,
                            String label) {
        Map<String, Object> observed = new LinkedHashMap<>();
        observed.put("mode", lock.mode().name());
        observed.put("requestedRetainUntil", requested.toString());
        observed.put("reportedRetainUntil", lock.retainUntil().toString());
        ev.put(label, observed);
        if (lock.mode() != mode || !requested.equals(lock.retainUntil())) {
            throw new CaseFailure(label + " is " + lock.mode() + " until " + lock.retainUntil() + ", expected " + mode
                    + " until " + requested);
        }
    }

    private static void expectCanonical(GcsObjectRetentionBucket bucket, String key, long generation, byte[] body) {
        StoredVersion oldest = bucket.oldest(key).orElseThrow(() -> new CaseFailure("nothing readable under " + key));
        if (!oldest.versionId().equals(Long.toString(generation)) || !Arrays.equals(oldest.bytes(), body)) {
            throw new CaseFailure("the canonical version of " + key + " is not generation " + generation);
        }
    }

    @FunctionalInterface
    private interface Probe {
        ProbeOutcome attempt(RetentionProbe probe, Gen target);
    }

    /** A refused probe that leaves the generation exactly as it was. */
    private void refusedUnchanged(Identity identity, Gen target, Map<String, Object> ev, String operation, Probe probe)
            throws InterruptedException {
        GenerationState before = state(target);
        ProbeOutcome outcome = mutate(identity, operation, p -> probe.attempt(p, target));
        GenerationState after = state(target);
        ev.put("target", target.name() + "#" + target.generation());
        ev.put("before", state(before));
        ev.put("outcome", outcome(outcome));
        ev.put("after", state(after));
        if (outcome.accepted()) {
            throw new CaseFailure("the provider accepted the operation");
        }
        int code = outcome.code();
        if (code < 400 || code == 401 || code == 404 || code == 412 || code == 429 || code >= 500) {
            throw new IllegalStateException("inconclusive refusal: HTTP " + code + " " + outcome.reason());
        }
        if (!before.exists() || !after.exists() || before.mode() != after.mode()
                || !Objects.equals(before.retainUntil(), after.retainUntil())) {
            throw new CaseFailure("the generation changed although the operation was refused");
        }
    }

    /** Mutations of one object are spaced by its metadata-update limit; a 429 is retried once. */
    private ProbeOutcome mutate(Identity identity, String operation, Function<RetentionProbe, ProbeOutcome> call)
            throws InterruptedException {
        provider.sleep(SAME_OBJECT_SPACING);
        ProbeOutcome outcome = probeCall(identity, operation, call);
        if (outcome.code() == 429) {
            provider.sleep(THROTTLE_BACKOFF);
            outcome = probeCall(identity, operation, call);
        }
        return outcome;
    }

    private ProbeOutcome probeCall(Identity identity, String operation, Function<RetentionProbe, ProbeOutcome> call) {
        ledger.beforeOperation(operation);
        try {
            ProbeOutcome outcome = call.apply(provider.probe(identity));
            ledger.afterOperation(operation, identity,
                    outcome.accepted() ? null : new StorageException(outcome.code(), outcome.message()));
            return outcome;
        } catch (RuntimeException ex) {
            ledger.afterOperation(operation, identity, ex);
            throw ex;
        }
    }

    private GenerationState state(Gen gen) {
        ledger.beforeOperation("objects.get");
        try {
            GenerationState state = provider.probe(Identity.READER).state(settings.bucket(), gen.name(), gen.generation());
            ledger.afterOperation("objects.get", Identity.READER, null);
            return state;
        } catch (RuntimeException ex) {
            ledger.afterOperation("objects.get", Identity.READER, ex);
            throw ex;
        }
    }

    private BucketSnapshot snapshot(String bucket) {
        ledger.beforeOperation("buckets.get");
        try {
            BucketSnapshot snapshot = provider.probe(Identity.READER).bucket(bucket);
            ledger.afterOperation("buckets.get", Identity.READER, null);
            return snapshot;
        } catch (RuntimeException ex) {
            ledger.afterOperation("buckets.get", Identity.READER, ex);
            throw ex;
        }
    }

    private static long highestSequence(DurableEvidenceBucket bucket) {
        long highest = 0;
        for (String key : bucket.keys("sequences/")) {
            String name = key.substring(key.lastIndexOf('/') + 1).replace(".json", "");
            highest = Math.max(highest, Long.parseLong(name));
        }
        return highest;
    }

    /** Pages per full enumeration of {@code key} (an enumeration ends with a page without a next token). */
    private static List<Integer> enumerations(List<InstrumentedGcsTransport.ListedPage> pages, String key) {
        List<Integer> counts = new ArrayList<>();
        int current = 0;
        for (InstrumentedGcsTransport.ListedPage page : pages) {
            if (!page.prefix().equals(key)) {
                continue;
            }
            current++;
            if (!page.hasNextPage()) {
                counts.add(current);
                current = 0;
            }
        }
        return counts;
    }

    private static String order(List<StoredVersion> versions) {
        List<Long> generations = versions.stream().map(v -> Long.parseLong(v.versionId())).toList();
        List<Long> ascending = generations.stream().sorted().toList();
        if (generations.equals(ascending)) {
            return "ascending";
        }
        List<Long> descending = new ArrayList<>(ascending);
        Collections.reverse(descending);
        return generations.equals(descending) ? "descending" : "other";
    }

    private static Long minimumIntervalMs(List<CreatedGeneration> writes) {
        Long minimum = null;
        for (int i = 1; i < writes.size(); i++) {
            long interval = Duration.between(writes.get(i - 1).createdAt(), writes.get(i).createdAt()).toMillis();
            minimum = minimum == null ? interval : Math.min(minimum, interval);
        }
        return minimum;
    }

    private Map<String, Object> precision(String key, DurableErasureReceipt receipt) {
        String name = storePrefix() + key;
        Map<String, Object> precision = new LinkedHashMap<>();
        ledger.created().stream()
                .filter(item -> item.name().equals(name) && Long.toString(item.generation()).equals(receipt.versionId()))
                .findFirst()
                .ifPresent(item -> {
                    precision.put("requested", item.retainUntil().toString());
                    precision.put("reported", receipt.retainUntil().toString());
                    precision.put("deltaNanos", Duration.between(receipt.retainUntil(), item.retainUntil()).toNanos());
                });
        return precision;
    }

    private Map<String, Object> verdict(RecoveryVerdict verdict) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("verdict", verdict.verdict().name());
        map.put("expectedThrough", verdict.expectedThrough());
        map.put("latestTrustedSequence", verdict.latestTrustedSequence());
        map.put("latestTrustedCheckpoint", verdict.latestTrustedCheckpoint());
        map.put("listedMaximumSequence", verdict.listedMaximumSequence());
        map.put("gaps", verdict.gaps());
        map.put("abandonedReservations", verdict.abandonedReservations());
        map.put("trustedRecords", verdict.pending().size());
        map.put("ignoredFrontierVersions", verdict.ignoredFrontierVersions());
        return map;
    }

    private static Map<String, Object> receipt(DurableErasureReceipt receipt) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("versionId", receipt.versionId());
        map.put("sha256", receipt.sha256());
        map.put("retentionMode", receipt.retentionMode());
        map.put("retainUntil", receipt.retainUntil().toString());
        return map;
    }

    private Map<String, Object> failure(Failure failure) {
        if (failure == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("operation", failure.operation());
        map.put("code", failure.code());
        map.put("reason", failure.reason());
        map.put("exception", failure.exception());
        return map;
    }

    private static Map<String, Object> outcome(ProbeOutcome outcome) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("accepted", outcome.accepted());
        map.put("code", outcome.code());
        map.put("reason", GcsAcceptanceLedger.sanitize(outcome.reason()));
        map.put("message", GcsAcceptanceLedger.sanitize(outcome.message()));
        return map;
    }

    private static Map<String, Object> state(GenerationState state) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("exists", state.exists());
        map.put("mode", state.mode() == null ? null : state.mode().name());
        map.put("retainUntil", state.retainUntil() == null ? null : state.retainUntil().toString());
        return map;
    }

    static Map<String, Object> describe(BucketSnapshot snapshot) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", snapshot.name());
        map.put("projectNumber", snapshot.projectNumber());
        map.put("location", snapshot.location());
        map.put("locationType", snapshot.locationType());
        map.put("storageClass", snapshot.storageClass());
        map.put("versioning", snapshot.versioning());
        map.put("objectRetention", snapshot.objectRetentionMode());
        map.put("retentionPolicySeconds", snapshot.retentionPolicySeconds());
        map.put("softDeleteSeconds", snapshot.softDeleteSeconds());
        map.put("uniformBucketLevelAccess", snapshot.uniformBucketLevelAccess());
        map.put("publicAccessPrevention", snapshot.publicAccessPrevention());
        map.put("hierarchicalNamespace", snapshot.hierarchicalNamespace());
        map.put("labels", snapshot.labels() == null ? null : new TreeMap<>(snapshot.labels()));
        map.put("timeCreated", snapshot.timeCreated());
        map.put("metageneration", snapshot.metageneration());
        return map;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
