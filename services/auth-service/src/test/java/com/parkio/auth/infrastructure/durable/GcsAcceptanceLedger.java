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
 * HTTP requests the live clients open (storage and token requests, retries included), each write
 * requested and each generation created (for cleanup), the case results, and the hard caps. A cap
 * stops the run before the next operation is sent and before the next HTTP connection is opened;
 * cloud billing cannot enforce one. Events also go to the run's {@link GcsAcceptanceJournal} as
 * they happen. Nothing secret is recorded: provider messages pass through {@link #sanitize(String)}.
 */
final class GcsAcceptanceLedger {

    /**
     * Hard stop thresholds; the offline plan of the full sequence leaves at least 20% headroom under
     * each. {@code httpRequests} counts storage and token requests together.
     */
    record Caps(int logicalOperations, int httpRequests, int generations, int complianceGenerations,
                Duration wallClock) {

        static final Caps APPROVED = new Caps(1_200, 1_500, 360, 40, Duration.ofMinutes(45));
    }

    /** Regional Standard storage list prices (USD), cloud.google.com/storage/pricing, read 2026-10-22. */
    static final double CLASS_A_USD_PER_1000 = 0.005;
    static final double CLASS_B_USD_PER_1000 = 0.0004;
    static final double STANDARD_USD_PER_GIB_MONTH = 0.02;

    enum Status { PASS, FAIL, MEASURED, SKIPPED, ERROR }

    /**
     * PREFLIGHT and ASSERTION cases must pass. A MEASUREMENT records a provider fact either way. A
     * DIAGNOSTIC runs only after a required case failed, to explain the failure; it never counts
     * towards a pass.
     */
    enum Kind { PREFLIGHT, ASSERTION, MEASUREMENT, DIAGNOSTIC }

    /** A failure the instrumented transport or a probe observed, as the cases inspect causes. */
    record Failure(String operation, int code, String reason, String exception) {
    }

    record CaseResult(String id, String title, Kind kind, Identity identity, Status status, String detail,
                      Map<String, Object> evidence, Instant startedAt, long durationMs, Map<String, Long> operations) {
    }

    record CreatedGeneration(String caseId, String name, long generation, EvidenceRetentionMode mode,
                             Instant retainUntil, int bytes, Instant createdAt) {
    }

    /** A write whose outcome is unknown (timeout, 408, 5xx, lost response): it may have created a generation. */
    record UnresolvedWrite(String caseId, String name, EvidenceRetentionMode mode, Instant retainUntil,
                           Instant requestedAt, String cause) {
    }

    private record PendingWrite(String caseId, String name, EvidenceRetentionMode mode, Instant retainUntil,
                                Instant requestedAt) {
    }

    /** Thrown when a cap is reached; the run stops and later cases are skipped. */
    static final class BudgetExceeded extends RuntimeException {
        BudgetExceeded(String message) {
            super(message);
        }
    }

    private static final Pattern TOKEN = Pattern.compile("ya29\\.[A-Za-z0-9_\\-.]+");
    private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+\\S+");
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]*");
    private static final Pattern PEM = Pattern.compile("-----BEGIN [A-Z ]+-----[\\s\\S]*?(-----END [A-Z ]+-----|$)");
    private static final Pattern SECRET_FIELD = Pattern.compile(
            "(?i)(access_?token|refresh_?token|id_?token|client_?secret|private_?key(?:_id)?)"
                    + "(\"?\\s*[:=]\\s*\"?)[^\",\\s}]+");

    private final Clock clock;
    private final Caps caps;
    private final Instant startedAt;
    private final AtomicLong httpRequests = new AtomicLong();
    private final AtomicLong authHttpRequests = new AtomicLong();
    private final Map<String, Map<String, Long>> operations = new TreeMap<>();
    private final List<CreatedGeneration> created = new ArrayList<>();
    private final Map<Long, PendingWrite> pending = new TreeMap<>();
    private final List<UnresolvedWrite> unresolved = new ArrayList<>();
    private final Map<String, String> violations = new TreeMap<>();
    private final List<Map<String, Object>> deleted = new ArrayList<>();
    private final List<CaseResult> cases = new ArrayList<>();
    private final ThreadLocal<Failure> lastFailure = new ThreadLocal<>();
    private long logicalOperations;
    private long possibleGenerations;
    private long possibleComplianceGenerations;
    private long chargedClassA;
    private long chargedClassB;
    private String abortReason;
    private String currentCase = "-";
    private Instant latestRequestedRetainUntil;
    private long requestedWrites;
    private long nextAttempt;
    private GcsAcceptanceJournal journal;

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

    /** The instant the wall-clock cap ends the run. */
    Instant deadline() {
        return startedAt.plus(caps.wallClock());
    }

    /** Sends every later event to {@code journal} too. */
    synchronized void attach(GcsAcceptanceJournal journal) {
        this.journal = Objects.requireNonNull(journal, "journal");
    }

    /**
     * Gate of every HTTP connection a live client opens, retries and token refreshes included: after
     * a stop, at the request cap or past the wall-clock cap the connection is refused before anything
     * is sent, so client retry loops cannot exceed the cap. The refusal is an unchecked exception,
     * which the client libraries do not retry.
     */
    synchronized void beforeHttpRequest(boolean tokenRequest) {
        if (abortReason != null) {
            throw new BudgetExceeded(abortReason);
        }
        String reached = null;
        if (httpRequests.get() + authHttpRequests.get() >= caps.httpRequests()) {
            reached = "HTTP request cap " + caps.httpRequests();
        } else if (clock.instant().isAfter(deadline())) {
            reached = "wall-clock cap " + caps.wallClock();
        }
        if (reached != null) {
            abort(reached + " reached before an HTTP request");
            throw new BudgetExceeded(abortReason);
        }
        (tokenRequest ? authHttpRequests : httpRequests).incrementAndGet();
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
        } else if (httpRequests.get() + authHttpRequests.get() >= caps.httpRequests()) {
            reached = "HTTP request cap " + caps.httpRequests();
        } else if (clock.instant().isAfter(deadline())) {
            reached = "wall-clock cap " + caps.wallClock();
        }
        if (reached != null) {
            abort(reached + " reached before " + operation);
            throw new BudgetExceeded(abortReason);
        }
        logicalOperations++;
    }

    /**
     * Reserves a write about to be sent, before the provider answers, and returns its attempt id.
     * The generation caps count every write that may have created a generation (acknowledged,
     * unresolved or in flight); check and reservation happen together, so concurrent writers cannot
     * pass a cap. Whether or not a response arrives, nothing the write may have created is retained
     * beyond {@code retainUntil}.
     */
    synchronized long writeRequested(String name, EvidenceRetentionMode mode, Instant retainUntil) {
        if (abortReason != null) {
            throw new BudgetExceeded(abortReason);
        }
        String reached = null;
        if (possibleGenerations >= caps.generations()) {
            reached = "generation cap " + caps.generations();
        } else if (mode == EvidenceRetentionMode.COMPLIANCE
                && possibleComplianceGenerations >= caps.complianceGenerations()) {
            reached = "COMPLIANCE generation cap " + caps.complianceGenerations();
        }
        if (reached != null) {
            abort(reached + " reached before a write");
            throw new BudgetExceeded(abortReason);
        }
        requestedWrites++;
        possibleGenerations++;
        if (mode == EvidenceRetentionMode.COMPLIANCE) {
            possibleComplianceGenerations++;
        }
        requested(retainUntil);
        long attempt = ++nextAttempt;
        pending.put(attempt, new PendingWrite(currentCase, name, mode, retainUntil, clock.instant()));
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("attempt", attempt);
        fields.put("case", currentCase);
        fields.put("name", name);
        fields.put("mode", mode.name());
        fields.put("retainUntil", retainUntil.toString());
        journal("write-requested", fields);
        return attempt;
    }

    /** The provider acknowledged attempt {@code attempt} with {@code generation}. */
    synchronized void writeAcknowledged(long attempt, long generation, int bytes) {
        PendingWrite write = Objects.requireNonNull(pending.remove(attempt), "unknown write attempt");
        created.add(new CreatedGeneration(write.caseId(), write.name(), generation, write.mode(), write.retainUntil(),
                bytes, clock.instant()));
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("attempt", attempt);
        fields.put("case", write.caseId());
        fields.put("name", write.name());
        fields.put("generation", Long.toString(generation));
        fields.put("mode", write.mode().name());
        fields.put("retainUntil", write.retainUntil().toString());
        journal("generation-created", fields);
    }

    /**
     * Settles a failed attempt: a definite refusal (a 4xx other than 408, or a request the gate never
     * sent) created nothing; anything else (no response, 408, 5xx) may have created a generation and
     * stays in the cleanup inventory as unresolved.
     */
    synchronized void writeFailed(long attempt, Throwable failure) {
        PendingWrite write = Objects.requireNonNull(pending.remove(attempt), "unknown write attempt");
        Failure observed = describe("objects.insert", failure);
        boolean refused = "stopped".equals(observed.reason())
                || observed.code() >= 400 && observed.code() < 500 && observed.code() != 408;
        if (refused) {
            possibleGenerations--;
            if (write.mode() == EvidenceRetentionMode.COMPLIANCE) {
                possibleComplianceGenerations--;
            }
            return;
        }
        unresolved.add(new UnresolvedWrite(write.caseId(), write.name(), write.mode(), write.retainUntil(),
                write.requestedAt(), observed.exception()));
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("attempt", attempt);
        fields.put("case", write.caseId());
        fields.put("name", write.name());
        fields.put("mode", write.mode().name());
        fields.put("retainUntil", write.retainUntil().toString());
        fields.put("cause", observed.exception());
        journal("write-unresolved", fields);
    }

    /**
     * A safety property the run exists to check was contradicted (for example an accepted change of
     * Locked retention): the run stops at once, the result carries the flag, and the operation is
     * not repeated.
     */
    synchronized void violation(String flag, String detail) {
        violations.putIfAbsent(flag, sanitize(detail));
        journal("violation", Map.of("flag", flag, "detail", sanitize(detail)));
        abort("stopped after a safety violation: " + flag);
    }

    synchronized Map<String, String> violations() {
        return Map.copyOf(violations);
    }

    /** Records a retention change about to be sent (an extension can move the cleanup time). */
    synchronized void retentionRequested(String name, long generation, Instant retainUntil) {
        requested(retainUntil);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("case", currentCase);
        fields.put("name", name);
        fields.put("generation", Long.toString(generation));
        fields.put("retainUntil", retainUntil.toString());
        journal("retention-requested", fields);
    }

    private void requested(Instant retainUntil) {
        if (latestRequestedRetainUntil == null || retainUntil.isAfter(latestRequestedRetainUntil)) {
            latestRequestedRetainUntil = retainUntil;
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
            if (failure instanceof InstrumentedGcsTransport.SimulatedLostResponse) {
                // The provider committed and charged the write; only its response is withheld.
                chargedClassA++;
            }
            Failure observed = describe(operation, failure);
            lastFailure.set(observed);
            outcome = observed.code() > 0 ? Integer.toString(observed.code())
                    : observed.exception().replaceFirst(":.*$", "");
        }
        operations.computeIfAbsent(operation + " as " + identity, ignored -> new TreeMap<>())
                .merge(outcome, 1L, Long::sum);
    }

    /** How often {@code operation} ended with {@code outcome} ("ok", an HTTP code or an exception), all identities. */
    synchronized long outcomes(String operation, String outcome) {
        return operations.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(operation + " as "))
                .mapToLong(entry -> entry.getValue().getOrDefault(outcome, 0L))
                .sum();
    }

    /** The most recent failure this thread observed since its last successful operation. */
    Failure lastFailure() {
        return lastFailure.get();
    }

    void clearLastFailure() {
        lastFailure.remove();
    }

    synchronized void deletedGeneration(String name, long generation) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("case", currentCase);
        item.put("name", name);
        item.put("generation", Long.toString(generation));
        deleted.add(item);
        journal("generation-deleted", new LinkedHashMap<>(item));
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
            journal("stopped", Map.of("reason", reason));
        }
    }

    synchronized void startCase(String id) {
        currentCase = id;
    }

    synchronized void record(CaseResult result) {
        cases.add(result);
        currentCase = "-";
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("case", result.id());
        fields.put("kind", result.kind().name());
        fields.put("status", result.status().name());
        fields.put("detail", result.detail());
        fields.put("durationMs", result.durationMs());
        fields.put("operations", result.operations());
        journal("case", fields);
    }

    private void journal(String event, Map<String, Object> fields) {
        if (journal != null) {
            journal.append(event, fields);
        }
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
        counters.put("tokenHttp", authHttpRequests.get());
        counters.put("writesRequested", requestedWrites);
        counters.put("possibleGenerations", possibleGenerations);
        counters.put("unresolvedWrites", (long) unresolved.size());
        return counters;
    }

    /**
     * The earliest full cleanup time: the latest retain-until of any generation this run created or
     * any write or retention change it requested, acknowledged or not (a lost response can hide a
     * generation, never a later retain-until than the one requested).
     */
    synchronized Instant latestRetainUntil() {
        Instant acknowledged = latestAcknowledgedRetainUntil();
        if (acknowledged == null) {
            return latestRequestedRetainUntil;
        }
        return latestRequestedRetainUntil == null || acknowledged.isAfter(latestRequestedRetainUntil)
                ? acknowledged : latestRequestedRetainUntil;
    }

    private Instant latestAcknowledgedRetainUntil() {
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
        generations.put("unresolvedWrites", unresolved.size());
        generations.put("possible", possibleGenerations);
        generations.put("possibleCompliance", possibleComplianceGenerations);
        generations.put("bytes", bytes);
        generations.put("deletedDuringRun", deleted.size());
        summary.put("generations", generations);
        Instant latest = latestRetainUntil();
        Instant acknowledged = latestAcknowledgedRetainUntil();
        summary.put("latestRetainUntil", latest == null ? null : latest.toString());
        summary.put("latestRetainUntilBasis", "every write and retention change requested, acknowledged or not");
        summary.put("latestAcknowledgedRetainUntil", acknowledged == null ? null : acknowledged.toString());
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

    /** Every acknowledged generation, then every write that may have created one ({@code generation: unknown}). */
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
        List<Object> open = new ArrayList<>(unresolved);
        open.addAll(pending.values());
        for (Object item : open) {
            Map<String, Object> entry = new LinkedHashMap<>();
            if (item instanceof UnresolvedWrite write) {
                entry.put("case", write.caseId());
                entry.put("name", write.name());
                entry.put("generation", "unknown");
                entry.put("mode", write.mode().name());
                entry.put("retainUntil", write.retainUntil().toString());
                entry.put("cause", write.cause());
            } else if (item instanceof PendingWrite write) {
                entry.put("case", write.caseId());
                entry.put("name", write.name());
                entry.put("generation", "unknown");
                entry.put("mode", write.mode().name());
                entry.put("retainUntil", write.retainUntil().toString());
                entry.put("cause", "no answer when the result was written");
            }
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

    /** Provider text without bearer tokens, JWTs, PEM blocks or credential fields, bounded in length. */
    static String sanitize(String text) {
        if (text == null) {
            return "";
        }
        String clean = PEM.matcher(text).replaceAll("<redacted-pem>");
        clean = TOKEN.matcher(clean).replaceAll("<redacted-token>");
        clean = JWT.matcher(clean).replaceAll("<redacted-jwt>");
        clean = BEARER.matcher(clean).replaceAll("Bearer <redacted>");
        clean = SECRET_FIELD.matcher(clean).replaceAll("$1$2<redacted>");
        clean = clean.replaceAll("\\s+", " ").trim();
        return clean.length() > 400 ? clean.substring(0, 400) + "..." : clean;
    }

    static Failure describe(String operation, Throwable failure) {
        for (Throwable link = failure; link != null; link = link.getCause()) {
            if (link instanceof BudgetExceeded stopped) {
                return new Failure(operation, 0, "stopped", "BudgetExceeded: " + sanitize(stopped.getMessage()));
            }
        }
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
