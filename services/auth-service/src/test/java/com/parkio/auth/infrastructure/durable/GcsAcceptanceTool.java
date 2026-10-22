package com.parkio.auth.infrastructure.durable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.parkio.auth.application.durable.EvidenceTrust;
import com.parkio.auth.application.durable.TrustedKey;
import com.parkio.auth.infrastructure.durable.GcsAcceptanceLedger.CaseResult;
import com.parkio.auth.infrastructure.durable.GcsAcceptanceLedger.Caps;
import com.parkio.auth.infrastructure.durable.GcsAcceptanceLedger.Kind;
import com.parkio.auth.infrastructure.durable.GcsAcceptanceLedger.Status;
import com.parkio.auth.infrastructure.durable.GcsAcceptanceProvider.Identity;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * N-02 real-provider acceptance harness (#483). Test classpath only, run through the auth-service
 * Gradle task {@code gcsProviderAcceptance}; never packaged in the service image.
 *
 * <ul>
 *   <li>{@code plan <out>}: runs the whole case sequence against the offline model, with no network,
 *       and writes {@code plan.json}: expected operations, generations, cost and timing. Not evidence.</li>
 *   <li>{@code run <out>}: runs it against the owner-approved test bucket and writes
 *       {@code result.json}, the synthetic {@code bundle.json} and {@code SHA256SUMS}. Only this
 *       counts for #483, and only from a clean checkout whose SHA the result records.</li>
 * </ul>
 *
 * <p>Before the first case the output directory holds {@code run.json} (run id, namespace, start,
 * deadline, the latest retain-until any write can request) and {@code journal.jsonl}, appended as
 * the run goes. The result is written exactly once: normally, on an internal error, by a shutdown
 * hook when the JVM is stopped ({@code INTERRUPTED}), or by a watchdog that ends the JVM when the
 * wall-clock cap plus {@link #WATCHDOG_GRACE} has passed.
 *
 * <p>Settings come from {@code N02_GCS_*} variables (no secrets). Storage credentials are keyless
 * Application Default Credentials from the dedicated {@code CLOUDSDK_CONFIG}, impersonating one
 * service account per identity; a service-account key is refused. The synthetic HMAC key exists
 * only in memory.
 */
public final class GcsAcceptanceTool {

    static final String FORMAT = "parkio-n02-gcs-provider-acceptance";
    static final Pattern BUCKET = Pattern.compile("parkio-n02-orl-accept-(\\d{8})-([0-9a-f]{6})");
    static final Pattern CONTROL_BUCKET = Pattern.compile("parkio-n02-noorl-ctrl-(\\d{8})-([0-9a-f]{6})");
    static final Pattern PROJECT = Pattern.compile("[a-z][a-z0-9-]{4,28}[a-z0-9]");
    static final Pattern PROJECT_NUMBER = Pattern.compile("[1-9][0-9]{5,19}");
    static final Pattern LOCATION = Pattern.compile("[a-z]+-[a-z]+[0-9]+");
    static final Pattern GIT_OBJECT = Pattern.compile("[0-9a-f]{40}");
    static final String PLAN_BUCKET = "parkio-n02-orl-accept-00000000-000000";
    static final String PLAN_CONTROL_BUCKET = "parkio-n02-noorl-ctrl-00000000-000000";
    static final String PLAN_LOCATION = "europe-west4";
    /**
     * After the wall-clock cap no request is sent; one already in flight ends within its timeouts and
     * the client's retry budget (20 s connect, 20 s read, 50 s of retries). After this grace the
     * watchdog writes the result and ends the JVM.
     */
    static final Duration WATCHDOG_GRACE = Duration.ofMinutes(2);
    /** The #483 criteria and the cases that must pass for each (MEASUREMENT and DIAGNOSTIC cases prove none). */
    static final Map<String, List<String>> CRITERIA = criteria();

    private static final Map<Identity, String> ACCOUNT_VARIABLES = Map.of(
            Identity.WRITER, "N02_GCS_WRITER_SA",
            Identity.WRITER_NO_DELETE, "N02_GCS_WRITER_NODELETE_SA",
            Identity.READER, "N02_GCS_READER_SA",
            Identity.PROBE_ADMIN, "N02_GCS_PROBE_ADMIN_SA");
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * The checkout the harness was built from, as the Gradle task reads it from git: {@code dirty} when
     * a build input has a tracked change or an untracked file; {@code status} lists them.
     */
    record Source(String sha, String tree, boolean dirty, String status) {

        Source(String sha, String tree, boolean dirty) {
            this(sha, tree, dirty, "");
        }

        static Source fromSystemProperties() {
            return new Source(System.getProperty("n02.source.sha", ""), System.getProperty("n02.source.tree", ""),
                    !"false".equals(System.getProperty("n02.source.dirty", "true")),
                    System.getProperty("n02.source.status", ""));
        }
    }

    /** The approved live operation; every field is validated before any provider call. */
    record LiveConfig(String project, String projectNumber, String location, String bucket, String controlBucket,
                      Map<Identity, String> accounts, String credentials) {

        static LiveConfig from(Map<String, String> env) {
            List<String> problems = new ArrayList<>();
            String logging = env.get("GOOGLE_SDK_JAVA_LOGGING");
            if (logging != null && !logging.isBlank() && !"false".equalsIgnoreCase(logging.trim())) {
                problems.add("GOOGLE_SDK_JAVA_LOGGING must be unset: the client libraries then log token responses");
            }
            String keyFile = env.get("GOOGLE_APPLICATION_CREDENTIALS");
            if (keyFile != null && !keyFile.isBlank()) {
                problems.add("GOOGLE_APPLICATION_CREDENTIALS must be unset: the run uses the gcloud login of the"
                        + " operation's own CLOUDSDK_CONFIG");
            }
            String config = env.get("CLOUDSDK_CONFIG");
            if (config == null || config.isBlank()) {
                problems.add("CLOUDSDK_CONFIG must name the operation's own gcloud configuration directory");
            }
            String project = value(env, "N02_GCS_PROJECT", PROJECT, problems);
            String projectNumber = value(env, "N02_GCS_PROJECT_NUMBER", PROJECT_NUMBER, problems);
            String location = value(env, "N02_GCS_LOCATION", LOCATION, problems);
            String bucket = value(env, "N02_GCS_BUCKET", BUCKET, problems);
            String control = value(env, "N02_GCS_CONTROL_BUCKET", CONTROL_BUCKET, problems);
            if (bucket != null && control != null) {
                Matcher main = BUCKET.matcher(bucket);
                Matcher other = CONTROL_BUCKET.matcher(control);
                if (main.matches() && other.matches()
                        && !(main.group(1) + main.group(2)).equals(other.group(1) + other.group(2))) {
                    problems.add("N02_GCS_CONTROL_BUCKET must carry the bucket's date and suffix");
                }
            }
            if (bucket != null && !bucket.equals(env.get("N02_GCS_CONFIRM"))) {
                problems.add("N02_GCS_CONFIRM must repeat N02_GCS_BUCKET exactly");
            }
            Map<Identity, String> accounts = new EnumMap<>(Identity.class);
            if (project != null) {
                Pattern account = Pattern.compile("[a-z][a-z0-9-]{4,28}[a-z0-9]@" + Pattern.quote(project)
                        + "\\.iam\\.gserviceaccount\\.com");
                ACCOUNT_VARIABLES.forEach((identity, variable) -> {
                    String email = value(env, variable, account, problems);
                    if (email != null) {
                        accounts.put(identity, email);
                    }
                });
                if (accounts.size() == Identity.values().length
                        && new HashSet<>(accounts.values()).size() != accounts.size()) {
                    problems.add("each identity needs its own service account");
                }
            }
            if (!problems.isEmpty()) {
                throw new IllegalArgumentException(String.join("; ", problems));
            }
            String directory = Path.of(config).getFileName() == null ? config : Path.of(config).getFileName().toString();
            return new LiveConfig(project, projectNumber, location, bucket, control, Map.copyOf(accounts),
                    "Application Default Credentials from CLOUDSDK_CONFIG .../" + directory);
        }

        private static String value(Map<String, String> env, String name, Pattern format, List<String> problems) {
            String value = env.get(name);
            if (value == null || value.isBlank()) {
                problems.add(name + " is required");
                return null;
            }
            if (!format.matcher(value).matches()) {
                problems.add(name + " does not match " + format.pattern());
                return null;
            }
            return value;
        }
    }

    /** Synthetic producer trust for this run; the HMAC key never leaves this process. */
    record Synthetic(EvidenceTrust trust, String keyId) {

        static Synthetic generate(String runId, Instant now) {
            byte[] secret = new byte[32];
            RANDOM.nextBytes(secret);
            TrustedKey key = TrustedKey.active("n02-accept-" + runId, "n02-gcs-acceptance", secret,
                    now.minus(Duration.ofMinutes(5)));
            Arrays.fill(secret, (byte) 0);
            String database = "postgresql:" + (7_000_000_000_000_000_000L + RANDOM.nextLong(1_000_000_000_000_000L))
                    + ":parkio_n02_acceptance";
            return new Synthetic(new EvidenceTrust(database, List.of(key)), key.keyId());
        }
    }

    /** Everything one invocation produced. */
    record Outcome(String verdict, Map<String, Object> document, byte[] bundle) {

        boolean violation() {
            return document.get("violations") instanceof Map<?, ?> violations && !violations.isEmpty();
        }
    }

    private GcsAcceptanceTool() {
    }

    public static void main(String[] args) {
        int code;
        try {
            code = execute(args, System.getenv(), Source.fromSystemProperties(), System.out);
        } catch (IllegalArgumentException ex) {
            System.err.println("refused: " + GcsAcceptanceLedger.sanitize(ex.getMessage()));
            code = 2;
        } catch (Throwable ex) {
            System.err.println("failed: " + ex.getClass().getSimpleName() + ": "
                    + GcsAcceptanceLedger.sanitize(ex.getMessage()));
            code = 4;
        }
        System.exit(code);
    }

    /**
     * Exit codes: 0 PASS; 1 FAIL; 2 refused before any provider call; 3 ABORTED or INTERRUPTED (a
     * threshold, the preflight or a stop); 4 ERROR (the harness failed); 5 FAIL with a safety violation,
     * after which the operation is not repeated.
     */
    static int execute(String[] args, Map<String, String> env, Source source, PrintStream out) throws IOException {
        if (args.length != 2 || !(args[0].equals("plan") || args[0].equals("run"))) {
            throw new IllegalArgumentException("usage: plan <empty output dir> | run <empty output dir>");
        }
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        requireEmpty(output);
        Outcome outcome;
        if (args[0].equals("plan")) {
            Files.createDirectories(output);
            outcome = recordedPlan(Instant.now(), source, out, output);
        } else {
            LiveConfig config = LiveConfig.from(env);
            if (!GIT_OBJECT.matcher(source.sha()).matches() || !GIT_OBJECT.matcher(source.tree()).matches()) {
                throw new IllegalArgumentException("the source SHA and tree are unknown; run through the Gradle task");
            }
            if (source.dirty()) {
                throw new IllegalArgumentException("build inputs differ from " + source.sha() + " ("
                        + GcsAcceptanceLedger.sanitize(source.status()) + "); live evidence needs a clean checkout");
            }
            Files.createDirectories(output);
            outcome = live(config, source, out, output);
        }
        summarize(outcome, output, out);
        return exitCode(outcome);
    }

    static int exitCode(Outcome outcome) {
        return switch (outcome.verdict()) {
            case "PASS" -> 0;
            case "FAIL" -> outcome.violation() ? 5 : 1;
            case "ERROR" -> 4;
            default -> 3;
        };
    }

    /** The full sequence against the offline model: expected operations, generations, cost, timing. */
    static Outcome plan(Instant start, Source source) {
        return plan(start, source, Set.of(), Caps.APPROVED, null, null);
    }

    static Outcome plan(Instant start, Source source, Set<OfflineGcsAcceptanceProvider.Defect> defects, Caps caps,
                        Synthetic synthetic, PrintStream progress) {
        return offlineRun(start, source, defects, caps, synthetic, progress).execute();
    }

    private static Run offlineRun(Instant start, Source source, Set<OfflineGcsAcceptanceProvider.Defect> defects,
                                  Caps caps, Synthetic synthetic, PrintStream progress) {
        OfflineGcsAcceptanceProvider provider = new OfflineGcsAcceptanceProvider(start, PLAN_BUCKET,
                PLAN_CONTROL_BUCKET, PLAN_LOCATION, defects);
        Map<Identity, String> accounts = new EnumMap<>(Identity.class);
        for (Identity identity : Identity.values()) {
            accounts.put(identity, "offline-" + identity.name().toLowerCase(Locale.ROOT).replace('_', '-'));
        }
        return new Run(provider, new GcsAcceptanceLedger(provider.clock(), caps), new LiveConfig("offline-plan",
                OfflineGcsAcceptanceProvider.PROJECT_NUMBER, PLAN_LOCATION, PLAN_BUCKET, PLAN_CONTROL_BUCKET, accounts,
                "none (offline model)"), source, synthetic, progress);
    }

    private static Outcome recordedPlan(Instant start, Source source, PrintStream progress, Path output)
            throws IOException {
        Run run = offlineRun(start, source, Set.of(), Caps.APPROVED, null, progress);
        Recorder recorder = Recorder.start(run, output, "plan.json", progress);
        return recorder.finish(run.execute());
    }

    private static Outcome live(LiveConfig config, Source source, PrintStream progress, Path output)
            throws IOException {
        GcsAcceptanceLedger ledger = new GcsAcceptanceLedger(Clock.systemUTC(), Caps.APPROVED);
        LiveGcsAcceptanceProvider provider = LiveGcsAcceptanceProvider.impersonating(config.project(),
                config.accounts(), ledger);
        Run run = new Run(provider, ledger, config, source, null, progress);
        Recorder recorder = Recorder.start(run, output, "result.json", progress);
        Thread hook = new Thread(() -> recorder.stop(run, "INTERRUPTED",
                "the JVM was stopped; written by the shutdown hook"), "n02-acceptance-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "n02-acceptance-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        long delay = Duration.between(Instant.now(), ledger.deadline().plus(WATCHDOG_GRACE)).toMillis();
        watchdog.schedule(() -> {
            recorder.stop(run, "ABORTED", "the wall-clock cap and its grace passed; written by the watchdog");
            Outcome written = recorder.written();
            System.exit(written == null ? 3 : exitCode(written));
        }, Math.max(0, delay), TimeUnit.MILLISECONDS);
        try {
            return recorder.finish(run.execute());
        } finally {
            watchdog.shutdownNow();
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException alreadyStopping) {
                // The JVM is shutting down; the hook finds the result written and returns.
            }
        }
    }

    /** One run: everything its result is built from, at the end or from the shutdown hook or watchdog. */
    static final class Run {

        private final GcsAcceptanceProvider provider;
        private final GcsAcceptanceLedger ledger;
        private final LiveConfig config;
        private final Source source;
        private final GcsAcceptanceCases.Settings settings;
        private final Synthetic trust;
        private final GcsAcceptanceCases cases;
        private final Instant started;

        Run(GcsAcceptanceProvider provider, GcsAcceptanceLedger ledger, LiveConfig config, Source source,
            Synthetic synthetic, PrintStream progress) {
            this.provider = provider;
            this.ledger = ledger;
            this.config = config;
            this.source = source;
            this.started = ledger.startedAt();
            String runId = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
                    .format(started) + "-" + String.format(Locale.ROOT, "%04x", RANDOM.nextInt(1 << 16));
            this.trust = synthetic == null ? Synthetic.generate(runId, started) : synthetic;
            this.settings = new GcsAcceptanceCases.Settings(config.bucket(), config.controlBucket(), config.location(),
                    config.projectNumber(), runId);
            this.cases = new GcsAcceptanceCases(provider, ledger, settings, trust.trust(), trust.keyId(), progress);
        }

        /** Runs every case; an exception or error still yields a result (verdict ERROR). */
        Outcome execute() {
            Throwable failure = null;
            try {
                cases.runAll();
            } catch (Throwable ex) {
                failure = ex;
                ledger.abort("harness error: " + ex.getClass().getSimpleName());
            }
            if (failure == null) {
                return outcome(null, null);
            }
            String verdict = ledger.violations().isEmpty() ? "ERROR" : "FAIL";
            return outcome(verdict, "harness error: " + failure.getClass().getSimpleName() + ": "
                    + GcsAcceptanceLedger.sanitize(failure.getMessage()));
        }

        /** What is known before the first case, written to run.json and the journal. */
        Map<String, Object> header() {
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("format", FORMAT);
            header.put("version", 2);
            header.put("evidenceClass", provider.evidenceClass());
            Map<String, Object> run = new LinkedHashMap<>();
            run.put("id", settings.runId());
            run.put("namespace", settings.prefix());
            run.put("startedAt", started.toString());
            run.put("deadline", ledger.deadline().toString());
            run.put("latestRetainUntilBound", latestRetainUntilBound().toString());
            header.put("run", run);
            Map<String, Object> sourceMap = new LinkedHashMap<>();
            sourceMap.put("sha", source.sha());
            sourceMap.put("tree", source.tree());
            sourceMap.put("buildInputsClean", !source.dirty());
            sourceMap.put("buildInputStatus", GcsAcceptanceLedger.sanitize(source.status()));
            header.put("source", sourceMap);
            Map<String, Object> resources = new LinkedHashMap<>();
            resources.put("project", config.project());
            resources.put("projectNumber", config.projectNumber());
            resources.put("location", config.location());
            resources.put("bucket", config.bucket());
            resources.put("controlBucket", config.controlBucket());
            header.put("resources", resources);
            Map<String, Object> identities = new LinkedHashMap<>();
            config.accounts().forEach((identity, account) -> identities.put(identity.name(), account));
            header.put("identities", identities);
            header.put("credentials", config.credentials());
            header.put("caps", ledger.summary().get("caps"));
            return header;
        }

        /**
         * No write of this run can request a later retain-until: writes stop at the deadline and ask
         * for at most the longer retention from their own start (K6's extension stays below it).
         */
        Instant latestRetainUntilBound() {
            Duration longest = GcsAcceptanceCases.GOVERNANCE_RETENTION.compareTo(GcsAcceptanceCases.COMPLIANCE_RETENTION)
                    >= 0 ? GcsAcceptanceCases.GOVERNANCE_RETENTION : GcsAcceptanceCases.COMPLIANCE_RETENTION;
            return ledger.deadline().plus(longest);
        }

        Outcome outcome(String forcedVerdict, String note) {
            // A recorded safety violation outranks how the run ended.
            String verdict = forcedVerdict != null && ledger.violations().isEmpty() ? forcedVerdict : verdict(ledger);
            Map<String, Object> document = header();
            boolean live = "live-provider".equals(provider.evidenceClass());
            document.put("countsFor483", live && "PASS".equals(verdict));
            document.put("verdict", verdict);
            if (note != null) {
                document.put("note", note);
            }
            document.put("violations", ledger.violations());
            document.put("criteria", criteriaStatus(ledger));
            document.put("checkpointUnderThrottling", cases.checkpointFacts());
            Map<String, Object> runtime = new LinkedHashMap<>();
            runtime.put("java", System.getProperty("java.version"));
            runtime.put("provider", provider.describe());
            runtime.put("adapter", List.of(GcsObjectRetentionBucket.class.getName(),
                    GoogleCloudStorageTransport.class.getName(), ObjectLockDurableErasureRecordStore.class.getName()));
            document.put("runtime", runtime);
            @SuppressWarnings("unchecked")
            Map<String, Object> run = (Map<String, Object>) document.get("run");
            run.put("finishedAt", provider.clock().instant().toString());
            run.put("elapsedSeconds", Duration.between(started, provider.clock().instant()).toSeconds());
            ledger.cases().stream().filter(result -> result.id().equals("F5")).findFirst()
                    .ifPresent(preflight -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> resources = (Map<String, Object>) document.get("resources");
                        resources.put("observed", preflight.evidence());
                    });
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("synthetic", true);
            data.put("producerKeyId", trust.keyId());
            data.put("producerKey", "generated in memory for this run; never written, printed or reused");
            data.put("databaseIdentity", trust.trust().databaseIdentity());
            data.put("governanceRetention", GcsAcceptanceCases.GOVERNANCE_RETENTION.toString());
            data.put("complianceRetention", GcsAcceptanceCases.COMPLIANCE_RETENTION.toString());
            document.put("data", data);
            document.put("budget", ledger.summary());
            document.put("cases", ledger.cases().stream().map(GcsAcceptanceTool::caseMap).toList());
            Map<String, Object> separation = new LinkedHashMap<>();
            separation.put("liveProviderEvidence", "cases F, P, H, C, T, S, L, K, G when evidenceClass is live-provider");
            separation.put("localVerifierOverLiveBytes", "cases V1 and V2: the existing verifier run in this process"
                    + " over bytes the read-only identity read from the live bucket");
            separation.put("notEvidence", List.of(
                    "offline-model plans and the harness's own unit tests",
                    "GcsObjectRetentionBucketTest and other in-memory transport tests (#482)",
                    "Spring wiring of GcsDurableStoreConfig (covered by GcsDurableStoreConfigTest)",
                    "Bucket Lock (bucket retention policy): out of scope"));
            document.put("separation", separation);
            document.put("cleanupInventory", ledger.cleanupInventory());
            document.put("deletedDuringRun", ledger.deletedDuringRun());
            return new Outcome(verdict, document, cases.bundle());
        }
    }

    /** Writes run.json first and the result files exactly once, whoever finishes the run. */
    static final class Recorder {

        private final Path output;
        private final String resultName;
        private final GcsAcceptanceJournal journal;
        private final Duration grace;
        private final AtomicBoolean claimed = new AtomicBoolean();
        private final CountDownLatch written = new CountDownLatch(1);
        private volatile Outcome writtenOutcome;

        private Recorder(Path output, String resultName, GcsAcceptanceJournal journal, Duration grace) {
            this.output = output;
            this.resultName = resultName;
            this.journal = journal;
            this.grace = grace;
        }

        static Recorder start(Run run, Path output, String resultName, PrintStream progress) throws IOException {
            return start(run, output, resultName, progress, Duration.ofSeconds(15));
        }

        /** {@code grace}: how long a stop waits for the main thread to write before writing itself. */
        static Recorder start(Run run, Path output, String resultName, PrintStream progress, Duration grace)
                throws IOException {
            GcsAcceptanceJournal journal = GcsAcceptanceJournal.create(output, run.provider.clock());
            run.ledger.attach(journal);
            Map<String, Object> header = run.header();
            Files.write(output.resolve("run.json"), JSON.writeValueAsBytes(header), StandardOpenOption.CREATE_NEW);
            journal.append("start", header);
            if (progress != null) {
                progress.println("run " + run.settings.runId() + ": gs://" + run.config.bucket() + "/"
                        + run.settings.prefix() + " started " + run.started + ", deadline " + run.ledger.deadline()
                        + "; nothing it writes is retained after " + run.latestRetainUntilBound());
            }
            return new Recorder(output, resultName, journal, grace);
        }

        Outcome finish(Outcome outcome) throws IOException {
            if (!claimed.compareAndSet(false, true)) {
                awaitWritten();
                return outcome;
            }
            try {
                journal.append("finish", Map.of("verdict", outcome.verdict()));
                journal.close();
                writeFiles(output, resultName, outcome);
                writtenOutcome = outcome;
            } finally {
                written.countDown();
            }
            return outcome;
        }

        /** The outcome in the written result, or null while none is written. */
        Outcome written() {
            return writtenOutcome;
        }

        /** From the shutdown hook or the watchdog: stop the run and write what it has, unless already written. */
        void stop(Run run, String verdict, String note) {
            if (claimed.get()) {
                awaitWritten();
                return;
            }
            run.ledger.abort(note);
            try {
                if (written.await(grace.toMillis(), TimeUnit.MILLISECONDS)) {
                    return;
                }
                finish(run.outcome(verdict, note + "; the case in progress did not finish"));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (IOException | RuntimeException ex) {
                System.err.println("could not write the result: " + ex.getClass().getSimpleName());
            }
        }

        private void awaitWritten() {
            try {
                written.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** PASS only when every PREFLIGHT and ASSERTION case passed and nothing stopped the run. */
    static String verdict(GcsAcceptanceLedger ledger) {
        if (!ledger.violations().isEmpty()) {
            return "FAIL";
        }
        if (ledger.aborted()) {
            return "ABORTED";
        }
        for (CaseResult result : ledger.cases()) {
            if (result.kind() == Kind.DIAGNOSTIC) {
                continue;
            }
            boolean measured = result.kind() == Kind.MEASUREMENT && result.status() == Status.MEASURED;
            if (result.status() != Status.PASS && !measured) {
                return "FAIL";
            }
        }
        return "PASS";
    }

    private static Map<String, List<String>> criteria() {
        Map<String, List<String>> criteria = new LinkedHashMap<>();
        criteria.put("retention feature discovery", List.of("F5", "F1", "F2", "F3", "F4", "F6"));
        criteria.put("put with retention", List.of("P1", "P2", "P3"));
        criteria.put("retention inspection by generation", List.of("P1", "P2", "P3", "K6"));
        criteria.put("generation listing and pagination", List.of("H1", "H2", "H3"));
        criteria.put("oldest generation canonical", List.of("P3", "H2", "C1", "C2"));
        criteria.put("concurrency and retry semantics",
                List.of("C1", "C2", "T1", "S1", "S2", "S3", "S4", "S5", "S5r", "S5s", "S6"));
        criteria.put("least-privilege failures", List.of("L1", "L3", "L4", "L5", "G5"));
        criteria.put("refusal of shortened or removed Locked retention",
                List.of("K1", "K2", "K3", "K4", "K5", "K7", "K8c", "K8", "K9", "K6", "G3", "G4"));
        criteria.put("Unlocked (GOVERNANCE) controls", List.of("G1", "G2", "G3", "G4"));
        criteria.put("verifier over live evidence (local verifier, live bytes)", List.of("V1", "V2"));
        return java.util.Collections.unmodifiableMap(criteria);
    }

    /**
     * Per criterion: PASS when every case passed or is a measurement that ran, FAIL when one failed,
     * INCOMPLETE otherwise; measurements are listed, since they prove nothing by themselves.
     */
    static Map<String, Object> criteriaStatus(GcsAcceptanceLedger ledger) {
        Map<String, CaseResult> results = new LinkedHashMap<>();
        ledger.cases().forEach(result -> results.put(result.id(), result));
        Map<String, Object> criteria = new LinkedHashMap<>();
        CRITERIA.forEach((criterion, ids) -> {
            String status = "PASS";
            List<String> measured = new ArrayList<>();
            for (String id : ids) {
                CaseResult result = results.get(id);
                Status observed = result == null ? Status.SKIPPED : result.status();
                if (observed == Status.FAIL || observed == Status.ERROR) {
                    status = "FAIL";
                    break;
                }
                if (observed == Status.MEASURED && result.kind() == Kind.MEASUREMENT) {
                    measured.add(id);
                } else if (observed != Status.PASS) {
                    status = "INCOMPLETE";
                }
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("status", status);
            entry.put("cases", ids);
            entry.put("measurementsOnly", measured);
            criteria.put(criterion, entry);
        });
        return criteria;
    }

    private static Map<String, Object> caseMap(CaseResult result) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", result.id());
        map.put("title", result.title());
        map.put("kind", result.kind().name());
        map.put("identity", result.identity().name());
        map.put("status", result.status().name());
        map.put("detail", result.detail());
        map.put("startedAt", result.startedAt().toString());
        map.put("durationMs", result.durationMs());
        map.put("operations", result.operations());
        map.put("evidence", result.evidence());
        return map;
    }

    private static void requireEmpty(Path output) throws IOException {
        if (Files.exists(output)) {
            try (var entries = Files.list(output)) {
                if (entries.findAny().isPresent()) {
                    throw new IllegalArgumentException("output directory is not empty: evidence is never overwritten");
                }
            }
        }
    }

    /** Writes the result and bundle (never over an existing file) and SHA256SUMS over every file of the run. */
    private static void writeFiles(Path output, String resultName, Outcome outcome) throws IOException {
        Files.write(output.resolve(resultName), JSON.writeValueAsBytes(outcome.document()),
                StandardOpenOption.CREATE_NEW);
        if (outcome.bundle() != null) {
            Files.write(output.resolve("bundle.json"), outcome.bundle(), StandardOpenOption.CREATE_NEW);
        }
        StringBuilder sums = new StringBuilder();
        for (String name : List.of("run.json", GcsAcceptanceJournal.FILE, resultName, "bundle.json")) {
            Path file = output.resolve(name);
            if (Files.exists(file)) {
                sums.append(GcsAcceptanceCases.sha256(Files.readAllBytes(file))).append("  ").append(name).append('\n');
            }
        }
        Files.writeString(output.resolve("SHA256SUMS"), sums.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);
    }

    @SuppressWarnings("unchecked")
    private static void summarize(Outcome outcome, Path output, PrintStream out) {
        Map<String, Object> document = outcome.document();
        Map<String, Object> budget = (Map<String, Object>) document.get("budget");
        out.println(document.get("evidenceClass") + " verdict=" + outcome.verdict() + " countsFor483="
                + document.get("countsFor483"));
        if (!((Map<String, Object>) document.get("violations")).isEmpty()) {
            out.println("SAFETY VIOLATION, do not repeat the operation: " + document.get("violations"));
        }
        out.println("checkpointUnderThrottling=" + document.get("checkpointUnderThrottling"));
        Map<String, Object> criteria = (Map<String, Object>) document.get("criteria");
        criteria.forEach((criterion, entry) -> out.println("criterion " + ((Map<String, Object>) entry).get("status")
                + ": " + criterion));
        out.println("counters=" + budget.get("counters"));
        out.println("generations=" + budget.get("generations"));
        out.println("listPriceEstimate=" + budget.get("listPriceEstimate"));
        out.println("latestRetainUntil=" + budget.get("latestRetainUntil")
                + " (no full cleanup before this instant; covers unanswered writes)");
        if (budget.get("abortReason") != null) {
            out.println("stopped: " + budget.get("abortReason"));
        }
        out.println("written to " + output);
    }
}
