package com.parkio.auth.infrastructure.durable;

import com.google.cloud.storage.StorageException;
import com.parkio.auth.domain.exception.AuthException;
import com.parkio.auth.infrastructure.durable.GcsAcceptanceProvider.Identity;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Accounting and stop thresholds of one #483 acceptance run: every logical storage operation, the
 * HTTP requests the live client sent, each generation the run created (for cleanup), the case
 * results, and the hard caps. A cap stops the run before the next operation is sent; cloud billing
 * cannot enforce one. Nothing secret is recorded: provider messages pass through
 * {@link #sanitize(String)}.
 */
final class GcsAcceptanceLedger {

    /** Hard stop thresholds; the offline plan of the full sequence leaves at least 20% headroom under each. */
    record Caps(int logicalOperations, int httpRequests, int generations, int complianceGenerations,
                Duration wallClock) {

        static final Caps APPROVED = new Caps(1_200, 1_500, 320, 40, Duration.ofMinutes(45));
    }

    /** Regional Standard storage list prices (USD), cloud.google.com/storage/pricing, read 2026-10-22. */
    static final double CLASS_A_USD_PER_1000 = 0.005;
    static final double CLASS_B_USD_PER_1000 = 0.0004;
    static final double STANDARD_USD_PER_GIB_MONTH = 0.02;

    enum Status { PASS, FAIL, MEASURED, SKIPPED, ERROR }

    enum Kind { PREFLIGHT, ASSERTION, MEASUREMENT }

    /** A failure the instrumented transport or a probe observed, as the cases inspect causes. */
    record Failure(String operation, int code, String reason, String exception) {
    }

    record CaseResult(String id, String title, Kind kind, Identity identity, Status status, String detail,
                      Map<String, Object> evidence, Instant startedAt, long durationMs, Map<String, Long> operations) {
    }

    record CreatedGeneration(String caseId, String name, long generation, EvidenceRetentionMode mode,
                             Instant retainUntil, int bytes, Instant createdAt) {
    }

    /** Thrown when a cap is reached; the run stops and later cases are skipped. */
    static final class BudgetExceeded extends RuntimeException {
        BudgetExceeded(String message) {
            super(message);
        }
    }

    private static final Pattern TOKEN = Pattern.compile("ya29\\.[A-Za-z0-9_\\-.]+");
    private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+\\S+");
    private static final Pattern SECRET_FIELD = Pattern.compile(
            "(?i)(access_token|refresh_token|id_token|client_secret|private_key)(\"?\\s*[:=]\\s*\"?)[^\",\\s}]+");

    private final Clock clock;
    private final Caps caps;
    private final Instant startedAt;
    private final AtomicLong httpRequests = new AtomicLong();
    private final Map<String, Map<String, Long>> operations = new TreeMap<>();
    private final List<CreatedGeneration> created = new ArrayList<>();
    private final List<Map<String, Object>> deleted = new ArrayList<>();
    private final List<CaseResult> cases = new ArrayList<>();
    private final ThreadLocal<Failure> lastFailure = new ThreadLocal<>();
    private long logicalOperations;
    private long chargedClassA;
    private long chargedClassB;
    private String abortReason;
    private String currentCase = "-";

    GcsAcceptanceLedger(Clock clock, Caps caps) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.caps = Objects.requireNonNull(caps, "caps");
        this.startedAt = clock.instant();
    }

    Caps caps() {
        return caps;
    }

    Instant startedAt() {
        return startedAt;
    }

    /** Counts one HTTP request the live storage client opened (retries included). */
    void httpRequest() {
        httpRequests.incrementAndGet();
    }

    long httpRequests() {
        return httpRequests.get();
    }

    /** Checks every cap before an operation is sent; a reached cap aborts the run. */
    synchronized void beforeOperation(String operation) {
        if (abortReason != null) {
            throw new BudgetExceeded(abortReason);
        }
        String reached = null;
        if (logicalOperations >= caps.logicalOperations()) {
            reached = "logical operation cap " + caps.logicalOperations();
        } else if (httpRequests.get() >= caps.httpRequests()) {
            reached = "HTTP request cap " + caps.httpRequests();
        } else if (Duration.between(startedAt, clock.instant()).compareTo(caps.wallClock()) > 0) {
            reached = "wall-clock cap " + caps.wallClock();
        } else if ("objects.insert".equals(operation) && created.size() >= caps.generations()) {
            reached = "generation cap " + caps.generations();
        }
        if (reached != null) {
            abortReason = reached + " reached before " + operation;
            throw new BudgetExceeded(abortReason);
        }
        logicalOperations++;
    }

    /** Refuses a COMPLIANCE write beyond the cap of irreversible generations. */
    synchronized void beforeComplianceWrite() {
        long compliance = created.stream().filter(c -> c.mode() == EvidenceRetentionMode.COMPLIANCE).count();
        if (compliance >= caps.complianceGenerations()) {
            abortReason = "COMPLIANCE generation cap " + caps.complianceGenerations() + " reached";
            throw new BudgetExceeded(abortReason);
        }
    }

    /** Records an operation's outcome; failures are kept per thread for the cases to inspect. */
    synchronized void afterOperation(String operation, Identity identity, Throwable failure) {
        String outcome;
        if (failure == null) {
            outcome = "ok";
            lastFailure.remove();
            if (isClassA(operation)) {
                chargedClassA++;
            } else if (isClassB(operation)) {
                chargedClassB++;
            }
        } else {
            Failure observed = describe(operation, failure);
            lastFailure.set(observed);
            outcome = observed.code() > 0 ? Integer.toString(observed.code())
                    : observed.exception().replaceFirst(":.*$", "");
        }
        operations.computeIfAbsent(operation + " as " + identity, ignored -> new TreeMap<>())
                .merge(outcome, 1L, Long::sum);
    }

    /** The most recent failure this thread observed since its last successful operation. */
    Failure lastFailure() {
        return lastFailure.get();
    }

    void clearLastFailure() {
        lastFailure.remove();
    }

    synchronized void created(String name, long generation, EvidenceRetentionMode mode, Instant retainUntil,
                              int bytes) {
        created.add(new CreatedGeneration(currentCase, name, generation, mode, retainUntil, bytes, clock.instant()));
    }

    synchronized void deletedGeneration(String name, long generation) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("case", currentCase);
        item.put("name", name);
        item.put("generation", Long.toString(generation));
        deleted.add(item);
    }

    synchronized List<CreatedGeneration> created() {
        return List.copyOf(created);
    }

    synchronized boolean aborted() {
        return abortReason != null;
    }

    synchronized String abortReason() {
        return abortReason;
    }

    synchronized void abort(String reason) {
        if (abortReason == null) {
            abortReason = reason;
        }
    }

    synchronized void startCase(String id) {
        currentCase = id;
    }

    synchronized void record(CaseResult result) {
        cases.add(result);
        currentCase = "-";
    }

    synchronized List<CaseResult> cases() {
        return List.copyOf(cases);
    }

    /** Operation counters for per-case deltas: classA/classB charged, total and failed. */
    synchronized Map<String, Long> counters() {
        long failed = operations.values().stream()
                .flatMap(byOutcome -> byOutcome.entrySet().stream())
                .filter(entry -> !"ok".equals(entry.getKey()))
                .mapToLong(Map.Entry::getValue)
                .sum();
        Map<String, Long> counters = new LinkedHashMap<>();
        counters.put("logical", logicalOperations);
        counters.put("chargedClassA", chargedClassA);
        counters.put("chargedClassB", chargedClassB);
        counters.put("failed", failed);
        counters.put("http", httpRequests.get());
        return counters;
    }

    /** The latest retain-until of any generation this run created: the earliest full cleanup time. */
    synchronized Instant latestRetainUntil() {
        return created.stream().map(CreatedGeneration::retainUntil).max(Instant::compareTo).orElse(null);
    }

    synchronized void extendRetainUntil(String name, long generation, Instant retainUntil) {
        for (int i = 0; i < created.size(); i++) {
            CreatedGeneration item = created.get(i);
            if (item.name().equals(name) && item.generation() == generation) {
                created.set(i, new CreatedGeneration(item.caseId(), name, generation, item.mode(), retainUntil,
                        item.bytes(), item.createdAt()));
            }
        }
    }

    /** Budget, counters, list-price cost and the cleanup inventory, ready for the result document. */
    synchronized Map<String, Object> summary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        Map<String, Object> capMap = new LinkedHashMap<>();
        capMap.put("logicalOperations", caps.logicalOperations());
        capMap.put("httpRequests", caps.httpRequests());
        capMap.put("generations", caps.generations());
        capMap.put("complianceGenerations", caps.complianceGenerations());
        capMap.put("wallClock", caps.wallClock().toString());
        summary.put("caps", capMap);
        summary.put("counters", counters());
        summary.put("operations", copy(operations));
        long governance = created.stream().filter(c -> c.mode() == EvidenceRetentionMode.GOVERNANCE).count();
        long compliance = created.stream().filter(c -> c.mode() == EvidenceRetentionMode.COMPLIANCE).count();
        long bytes = created.stream().mapToLong(CreatedGeneration::bytes).sum();
        Map<String, Object> generations = new LinkedHashMap<>();
        generations.put("governance", governance);
        generations.put("compliance", compliance);
        generations.put("bytes", bytes);
        generations.put("deletedDuringRun", deleted.size());
        summary.put("generations", generations);
        Instant latest = latestRetainUntil();
        summary.put("latestRetainUntil", latest == null ? null : latest.toString());
        double operationsUsd = chargedClassA * CLASS_A_USD_PER_1000 / 1000 + chargedClassB * CLASS_B_USD_PER_1000 / 1000;
        double hours = latest == null ? 0 : Math.max(0, Duration.between(startedAt, latest).toMinutes() / 60.0);
        double storageUsd = bytes / (1024.0 * 1024 * 1024) * STANDARD_USD_PER_GIB_MONTH * hours / 730.0;
        Map<String, Object> cost = new LinkedHashMap<>();
        cost.put("pricing", "regional Standard list price, flat namespace, read 2026-10-22");
        cost.put("operationsUsd", round(operationsUsd));
        cost.put("storageUsdUntilLatestRetainUntil", round(storageUsd));
        summary.put("listPriceEstimate", cost);
        summary.put("abortReason", abortReason);
        return summary;
    }

    synchronized List<Map<String, Object>> cleanupInventory() {
        List<Map<String, Object>> inventory = new ArrayList<>();
        for (CreatedGeneration item : created) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("case", item.caseId());
            entry.put("name", item.name());
            entry.put("generation", Long.toString(item.generation()));
            entry.put("mode", item.mode().name());
            entry.put("retainUntil", item.retainUntil().toString());
            inventory.add(entry);
        }
        return inventory;
    }

    synchronized List<Map<String, Object>> deletedDuringRun() {
        return List.copyOf(deleted);
    }

    static boolean isClassA(String operation) {
        return operation.equals("objects.insert") || operation.equals("objects.list")
                || operation.equals("objects.patch");
    }

    static boolean isClassB(String operation) {
        return operation.equals("objects.get") || operation.equals("objects.get.media")
                || operation.equals("buckets.get");
    }

    /** Provider text without bearer tokens or credential fields, bounded in length. */
    static String sanitize(String text) {
        if (text == null) {
            return "";
        }
        String clean = TOKEN.matcher(text).replaceAll("<redacted-token>");
        clean = BEARER.matcher(clean).replaceAll("Bearer <redacted>");
        clean = SECRET_FIELD.matcher(clean).replaceAll("$1$2<redacted>");
        clean = clean.replaceAll("\\s+", " ").trim();
        return clean.length() > 400 ? clean.substring(0, 400) + "..." : clean;
    }

    static Failure describe(String operation, Throwable failure) {
        Throwable cause = failure;
        while (cause instanceof AuthException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof StorageException storage) {
            return new Failure(operation, storage.getCode(), sanitize(storage.getReason()),
                    StorageException.class.getSimpleName() + ": " + sanitize(storage.getMessage()));
        }
        return new Failure(operation, 0, "", cause.getClass().getSimpleName() + ": " + sanitize(cause.getMessage()));
    }

    private static Map<String, Object> copy(Map<String, Map<String, Long>> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, new TreeMap<>(value)));
        return copy;
    }

    private static double round(double usd) {
        return Math.round(usd * 1_000_000d) / 1_000_000d;
    }
}
