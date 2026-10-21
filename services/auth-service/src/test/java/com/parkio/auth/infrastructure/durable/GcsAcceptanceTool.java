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
 * <p>Settings come from {@code N02_GCS_*} variables (no secrets). Storage credentials are
 * Application Default Credentials impersonating one service account per identity; a
 * service-account key file is refused. The synthetic HMAC key exists only in memory.
 */
public final class GcsAcceptanceTool {

    static final String FORMAT = "parkio-n02-gcs-provider-acceptance";
    static final Pattern BUCKET = Pattern.compile("parkio-n02-orl-accept-(\\d{8})-([0-9a-f]{6})");
    static final Pattern CONTROL_BUCKET = Pattern.compile("parkio-n02-noorl-ctrl-(\\d{8})-([0-9a-f]{6})");
    static final Pattern PROJECT = Pattern.compile("[a-z][a-z0-9-]{4,28}[a-z0-9]");
    static final Pattern LOCATION = Pattern.compile("[a-z]+-[a-z]+[0-9]+");
    static final Pattern GIT_OBJECT = Pattern.compile("[0-9a-f]{40}");
    static final String PLAN_BUCKET = "parkio-n02-orl-accept-00000000-000000";
    static final String PLAN_CONTROL_BUCKET = "parkio-n02-noorl-ctrl-00000000-000000";
    static final String PLAN_LOCATION = "europe-west4";

    private static final Map<Identity, String> ACCOUNT_VARIABLES = Map.of(
            Identity.WRITER, "N02_GCS_WRITER_SA",
            Identity.WRITER_NO_DELETE, "N02_GCS_WRITER_NODELETE_SA",
            Identity.READER, "N02_GCS_READER_SA",
            Identity.PROBE_ADMIN, "N02_GCS_PROBE_ADMIN_SA");
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** The checkout the harness was built from, as the Gradle task reads it from git. */
    record Source(String sha, String tree, boolean dirty) {

        static Source fromSystemProperties() {
            return new Source(System.getProperty("n02.source.sha", ""), System.getProperty("n02.source.tree", ""),
                    !"false".equals(System.getProperty("n02.source.dirty", "true")));
        }
    }

    /** The approved live operation; every field is validated before any provider call. */
    record LiveConfig(String project, String location, String bucket, String controlBucket,
                      Map<Identity, String> accounts) {

        static LiveConfig from(Map<String, String> env) {
            List<String> problems = new ArrayList<>();
            String project = value(env, "N02_GCS_PROJECT", PROJECT, problems);
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
            return new LiveConfig(project, location, bucket, control, Map.copyOf(accounts));
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
        } catch (Exception ex) {
            System.err.println("failed: " + ex.getClass().getSimpleName() + ": "
                    + GcsAcceptanceLedger.sanitize(ex.getMessage()));
            code = 4;
        }
        System.exit(code);
    }

    /** Exit codes: 0 PASS, 1 FAIL, 2 refused before any provider call, 3 stopped by a threshold or preflight. */
    static int execute(String[] args, Map<String, String> env, Source source, PrintStream out) throws IOException {
        if (args.length != 2 || !(args[0].equals("plan") || args[0].equals("run"))) {
            throw new IllegalArgumentException("usage: plan <empty output dir> | run <empty output dir>");
        }
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        requireEmpty(output);
        Outcome outcome;
        if (args[0].equals("plan")) {
            outcome = plan(Instant.now(), source, Set.of(), Caps.APPROVED, null, out);
            write(output, "plan.json", outcome);
        } else {
            LiveConfig config = LiveConfig.from(env);
            if (!GIT_OBJECT.matcher(source.sha()).matches() || !GIT_OBJECT.matcher(source.tree()).matches()) {
                throw new IllegalArgumentException("the source SHA and tree are unknown; run through the Gradle task");
            }
            if (source.dirty()) {
                throw new IllegalArgumentException("tracked files differ from " + source.sha()
                        + "; live evidence needs a clean checkout");
            }
            outcome = live(config, source, out);
            write(output, "result.json", outcome);
        }
        summarize(outcome, output, out);
        return switch (outcome.verdict()) {
            case "PASS" -> 0;
            case "FAIL" -> 1;
            default -> 3;
        };
    }

    /** The full sequence against the offline model: expected operations, generations, cost, timing. */
    static Outcome plan(Instant start, Source source) {
        return plan(start, source, Set.of(), Caps.APPROVED, null, null);
    }

    static Outcome plan(Instant start, Source source, Set<OfflineGcsAcceptanceProvider.Defect> defects, Caps caps,
                        Synthetic synthetic, PrintStream progress) {
        OfflineGcsAcceptanceProvider provider = new OfflineGcsAcceptanceProvider(start, PLAN_BUCKET,
                PLAN_CONTROL_BUCKET, PLAN_LOCATION, defects);
        Map<Identity, String> accounts = new EnumMap<>(Identity.class);
        for (Identity identity : Identity.values()) {
            accounts.put(identity, "offline-" + identity.name().toLowerCase(Locale.ROOT).replace('_', '-'));
        }
        return execute(provider, new GcsAcceptanceLedger(provider.clock(), caps), new LiveConfig("offline-plan",
                PLAN_LOCATION, PLAN_BUCKET, PLAN_CONTROL_BUCKET, accounts), source, synthetic, progress);
    }

    private static Outcome live(LiveConfig config, Source source, PrintStream progress) throws IOException {
        GcsAcceptanceLedger ledger = new GcsAcceptanceLedger(Clock.systemUTC(), Caps.APPROVED);
        LiveGcsAcceptanceProvider provider = LiveGcsAcceptanceProvider.impersonating(config.project(),
                config.accounts(), ledger);
        progress.println("live run against gs://" + config.bucket() + " (" + config.location() + "), source "
                + source.sha());
        return execute(provider, ledger, config, source, null, progress);
    }

    private static Outcome execute(GcsAcceptanceProvider provider, GcsAcceptanceLedger ledger, LiveConfig config,
                                   Source source, Synthetic synthetic, PrintStream progress) {
        Instant started = provider.clock().instant();
        String runId = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(started)
                + "-" + String.format(Locale.ROOT, "%04x", RANDOM.nextInt(1 << 16));
        Synthetic trust = synthetic == null ? Synthetic.generate(runId, started) : synthetic;
        GcsAcceptanceCases.Settings settings = new GcsAcceptanceCases.Settings(config.bucket(), config.controlBucket(),
                config.location(), runId);
        GcsAcceptanceCases cases = new GcsAcceptanceCases(provider, ledger, settings, trust.trust(), trust.keyId(),
                progress);
        cases.runAll();
        String verdict = verdict(ledger);
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("format", FORMAT);
        document.put("version", 1);
        document.put("evidenceClass", provider.evidenceClass());
        document.put("countsFor483", "live-provider".equals(provider.evidenceClass()) && "PASS".equals(verdict));
        document.put("verdict", verdict);
        Map<String, Object> sourceMap = new LinkedHashMap<>();
        sourceMap.put("sha", source.sha());
        sourceMap.put("tree", source.tree());
        sourceMap.put("trackedFilesClean", !source.dirty());
        document.put("source", sourceMap);
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("java", System.getProperty("java.version"));
        runtime.put("provider", provider.describe());
        runtime.put("adapter", List.of(GcsObjectRetentionBucket.class.getName(), GoogleCloudStorageTransport.class.getName(),
                ObjectLockDurableErasureRecordStore.class.getName()));
        document.put("runtime", runtime);
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("id", runId);
        run.put("namespace", settings.prefix());
        run.put("startedAt", started.toString());
        run.put("finishedAt", provider.clock().instant().toString());
        run.put("elapsedSeconds", Duration.between(started, provider.clock().instant()).toSeconds());
        document.put("run", run);
        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("project", config.project());
        resources.put("location", config.location());
        resources.put("bucket", config.bucket());
        resources.put("controlBucket", config.controlBucket());
        ledger.cases().stream().filter(result -> result.id().equals("F5")).findFirst()
                .ifPresent(preflight -> resources.put("observed", preflight.evidence()));
        document.put("resources", resources);
        Map<String, Object> identities = new LinkedHashMap<>();
        config.accounts().forEach((identity, account) -> identities.put(identity.name(), account));
        document.put("identities", identities);
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
        separation.put("liveProviderEvidence", "cases F, P, H, C, S, L, K, G when evidenceClass is live-provider");
        separation.put("localVerifierOverLiveBytes", "cases V1 and V2: the existing verifier run in this process over"
                + " bytes the read-only identity read from the live bucket");
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

    static String verdict(GcsAcceptanceLedger ledger) {
        if (ledger.aborted()) {
            return "ABORTED";
        }
        for (CaseResult result : ledger.cases()) {
            boolean measured = result.kind() == Kind.MEASUREMENT && result.status() == Status.MEASURED;
            if (result.status() != Status.PASS && !measured) {
                return "FAIL";
            }
        }
        return "PASS";
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

    private static void write(Path output, String resultName, Outcome outcome) throws IOException {
        Files.createDirectories(output);
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(resultName, JSON.writeValueAsBytes(outcome.document()));
        if (outcome.bundle() != null) {
            files.put("bundle.json", outcome.bundle());
        }
        StringBuilder sums = new StringBuilder();
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            Files.write(output.resolve(file.getKey()), file.getValue());
            sums.append(GcsAcceptanceCases.sha256(file.getValue())).append("  ").append(file.getKey()).append('\n');
        }
        Files.writeString(output.resolve("SHA256SUMS"), sums.toString(), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static void summarize(Outcome outcome, Path output, PrintStream out) {
        Map<String, Object> budget = (Map<String, Object>) outcome.document().get("budget");
        out.println(outcome.document().get("evidenceClass") + " verdict=" + outcome.verdict());
        out.println("counters=" + budget.get("counters"));
        out.println("generations=" + budget.get("generations"));
        out.println("listPriceEstimate=" + budget.get("listPriceEstimate"));
        out.println("latestRetainUntil=" + budget.get("latestRetainUntil")
                + " (no full cleanup before this instant)");
        if (budget.get("abortReason") != null) {
            out.println("stopped: " + budget.get("abortReason"));
        }
        out.println("written to " + output);
    }
}
