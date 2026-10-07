package com.parkio.auth.infrastructure.recovery;

import com.parkio.auth.application.durable.DatabaseIdentity;
import com.parkio.auth.application.durable.DurableErasureEvidenceVerifier;
import com.parkio.auth.application.durable.DurableEvidenceException;
import com.parkio.auth.application.durable.ErasureLedgerEntry;
import com.parkio.auth.application.durable.EvidenceTrust;
import com.parkio.auth.application.durable.TrustedErasureSet;
import com.parkio.auth.application.durable.TrustedErasureSetDocument;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * Every recovery-replay check that can refuse, run on the prepared environment before any Spring
 * context exists (PR #295 review B5): nothing is migrated, written, subscribed or scheduled until
 * all of them pass. The target is read over a plain, read-only JDBC connection. In order:
 *
 * <ol>
 *   <li>the {@value RecoveryReplayLaunch#PROFILE} profile, and no web application type other than
 *       none (20);</li>
 *   <li>the restore-replay flag (20) and every durable-store writer off (20): a restored copy must
 *       never write into the real evidence store;</li>
 *   <li>a positive poll interval (20);</li>
 *   <li>no setting that points a connection anywhere but {@code spring.datasource.url}, which must
 *       be one plain PostgreSQL host and database (22);</li>
 *   <li>the trust document and the trusted-set file, re-verified from the embedded bundle (21);</li>
 *   <li>the attempt and dataset against the file (23);</li>
 *   <li>the connected database: readable, unambiguous, not on the production cluster pinned in
 *       the trust document whatever its database name, and the ticket's target (22);</li>
 *   <li>the backup anchor: every erasure the restored auth database marks
 *       {@code DURABLY_RECORDED}, whatever the request status, is in the trusted set (21).</li>
 * </ol>
 */
final class RecoveryReplayPreflight {

    static final String FLAG = "parkio.privacy.account-erasure.restore-replay.enabled";
    /** Durable-store writers, by property; each must be off for a recovery replay. */
    static final List<String> WRITERS = List.of(
            "parkio.privacy.account-erasure.durable-recording-enabled",
            "parkio.privacy.account-erasure.durable-recording-retry-worker-enabled",
            "parkio.privacy.account-erasure.durable-store.object-lock.enabled",
            "parkio.privacy.account-erasure.durable-store.checkpoint.enabled");
    static final String POLL_INTERVAL = "parkio.privacy.account-erasure.recovery-replay.poll-interval";
    static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(5);
    static final String WEB_APPLICATION_TYPE = "spring.main.web-application-type";
    /** Settings that would give a connection of the command context another target (B6). */
    static final List<String> REDIRECTS = List.of(
            "spring.flyway.url", "spring.flyway.user", "spring.flyway.password", "spring.flyway.driver-class-name",
            "spring.datasource.hikari.jdbc-url", "spring.datasource.hikari.username",
            "spring.datasource.hikari.password", "spring.datasource.hikari.data-source-class-name",
            "spring.datasource.hikari.data-source-j-n-d-i", "spring.datasource.jndi-name",
            "spring.datasource.xa.data-source-class-name");
    static final List<String> REDIRECT_MAPS = List.of(
            "spring.datasource.hikari.data-source-properties", "spring.datasource.xa.properties");
    /** {@code jdbc:postgresql://<one host[:port]>/<database>[?parameters]}: no host list, no service. */
    private static final Pattern SINGLE_POSTGRES_URL =
            Pattern.compile("jdbc:postgresql://([^/?#,\\s]+)/([^/?#\\s]+)(?:\\?([^#\\s]*))?");
    /** URL parameters that cannot change which server or database is reached (lower case). */
    static final Set<String> URL_PARAMETERS = Set.of(
            "ssl", "sslmode", "sslrootcert", "connecttimeout", "sockettimeout", "logintimeout", "applicationname",
            "tcpkeepalive");

    /** What the checks established; the command context runs only with this. */
    record Plan(RecoveryReplayArguments arguments, TrustedErasureSet set, String connectedIdentity,
                Duration pollInterval) {
    }

    private final Clock clock;
    private final Function<Environment, RecoveryReplayTarget> targets;

    RecoveryReplayPreflight(Clock clock, Function<Environment, RecoveryReplayTarget> targets) {
        this.clock = clock;
        this.targets = targets;
    }

    /** The production preflight: the target is the configured datasource, read over plain JDBC. */
    static RecoveryReplayPreflight forDatasource() {
        return new RecoveryReplayPreflight(Clock.systemUTC(), environment -> RecoveryReplayTarget.jdbc(
                environment.getProperty("spring.datasource.url"),
                environment.getProperty("spring.datasource.username"),
                environment.getProperty("spring.datasource.password")));
    }

    /** Runs every check; returns the plan or throws {@link RecoveryReplayRefusal}. */
    Plan check(Environment environment, RecoveryReplayArguments arguments, RecoveryReplayVerdict verdict) {
        if (!environment.matchesProfiles(RecoveryReplayLaunch.PROFILE)) {
            throw refuse(RecoveryReplayExit.REFUSED, "the " + RecoveryReplayLaunch.PROFILE + " profile is not active");
        }
        String webType = environment.getProperty(WEB_APPLICATION_TYPE);
        if (webType != null && !"none".equalsIgnoreCase(webType.trim())) {
            throw refuse(RecoveryReplayExit.REFUSED,
                    "the command runs without a web server; " + WEB_APPLICATION_TYPE + "=" + webType + " is refused");
        }
        if (!flag(environment, FLAG)) {
            throw refuse(RecoveryReplayExit.REFUSED, "restore replay is disabled (" + FLAG + "=false)");
        }
        List<String> enabledWriters = WRITERS.stream().filter(writer -> flag(environment, writer)).toList();
        if (!enabledWriters.isEmpty()) {
            throw refuse(RecoveryReplayExit.REFUSED,
                    "a durable-store writer is enabled; a restored copy must not write evidence: " + enabledWriters);
        }
        Duration pollInterval = pollInterval(environment);
        checkDatasourceSettings(environment);

        EvidenceTrust trust = trust(arguments.trust());
        TrustedErasureSetDocument document;
        TrustedErasureSet set;
        try {
            document = TrustedErasureSetDocument.parse(read(arguments.evidence(), "trusted-set file"));
            set = document.verify(new DurableErasureEvidenceVerifier(trust, clock.instant()), trust.databaseIdentity());
        } catch (DurableEvidenceException ex) {
            throw refuse(RecoveryReplayExit.INVALID_EVIDENCE, "evidence does not verify: " + ex.getMessage());
        }
        verdict.set(set);

        if (!document.recoveryAttemptId().equals(arguments.attempt().toString())
                || !document.restoredDatasetId().equals(arguments.dataset())) {
            throw refuse(RecoveryReplayExit.ATTEMPT_MISMATCH,
                    "the attempt or dataset does not match the trusted-set file");
        }

        try (RecoveryReplayTarget target = targets.apply(environment)) {
            String connected = connectedIdentity(target);
            checkTarget(connected, arguments.targetIdentity(), document.targetIdentity(), trust.databaseIdentity());
            checkAnchor(target, set);
            return new Plan(arguments, set, connected, pollInterval);
        }
    }

    /**
     * The preflight checks the database named by {@code spring.datasource.url}; nothing in the
     * command context may connect anywhere else (PR #295 review B6, layer 1). Every setting that
     * can point a connection elsewhere is refused (22), whatever its value: Flyway's own
     * connection, the pool's own URL, credentials or data-source properties, JNDI and XA data
     * sources. The URL itself must name one PostgreSQL host and one database, with only the
     * parameters in {@link #URL_PARAMETERS}. The command context re-checks every connection it
     * actually opens (layer 2, {@link RecoveryReplayConnectionGuard}).
     */
    static void checkDatasourceSettings(Environment environment) {
        Binder binder = Binder.get(environment);
        for (String property : REDIRECTS) {
            if (binder.bind(property, Bindable.of(String.class)).isBound()) {
                throw refuse(RecoveryReplayExit.TARGET_REFUSED, property
                        + " is set; the command connects only to the checked spring.datasource.url");
            }
        }
        for (String map : REDIRECT_MAPS) {
            if (binder.bind(map, Bindable.mapOf(String.class, String.class)).isBound()) {
                throw refuse(RecoveryReplayExit.TARGET_REFUSED, map
                        + " is set; the command connects only to the checked spring.datasource.url");
            }
        }
        String url = environment.getProperty("spring.datasource.url");
        if (!singlePostgresUrl(url)) {
            throw refuse(RecoveryReplayExit.TARGET_REFUSED,
                    "spring.datasource.url is not a single-host PostgreSQL URL with allowed parameters only");
        }
    }

    static boolean singlePostgresUrl(String url) {
        Matcher matcher = url == null ? null : SINGLE_POSTGRES_URL.matcher(url);
        if (matcher == null || !matcher.matches()) {
            return false;
        }
        String query = matcher.group(3);
        if (query == null) {
            return true;
        }
        for (String parameter : query.split("&", -1)) {
            String name = parameter.contains("=") ? parameter.substring(0, parameter.indexOf('=')) : parameter;
            if (!URL_PARAMETERS.contains(name.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    private static String connectedIdentity(RecoveryReplayTarget target) {
        String connected;
        try {
            connected = target.identity();
        } catch (RuntimeException ex) {
            throw refuse(RecoveryReplayExit.TARGET_REFUSED, "the connected database identity is unreadable");
        }
        if (connected == null || connected.isBlank()) {
            throw refuse(RecoveryReplayExit.TARGET_REFUSED, "the connected database identity is unreadable");
        }
        return connected;
    }

    /**
     * The connected database and the ticket's target must each be unambiguous and off the
     * production cluster (same {@code system_identifier}, any database name), and must be the same.
     */
    static void checkTarget(String connected, String ticketTarget, String documentTarget, String production) {
        for (String identity : List.of(connected, ticketTarget)) {
            try {
                DatabaseIdentity.checkTarget(identity, production);
            } catch (DurableEvidenceException ex) {
                throw refuse(RecoveryReplayExit.TARGET_REFUSED, "the target is refused: " + ex.getMessage());
            }
        }
        if (!connected.equals(ticketTarget) || !documentTarget.equals(ticketTarget)) {
            throw refuse(RecoveryReplayExit.TARGET_REFUSED,
                    "the connected database is not the isolated target named by the ticket");
        }
    }

    /** Evidence older than the backup lacks erasures the restored auth database already recorded. */
    private static void checkAnchor(RecoveryReplayTarget target, TrustedErasureSet set) {
        List<UUID> recorded;
        try {
            recorded = target.durablyRecordedUsers();
        } catch (RuntimeException ex) {
            throw refuse(RecoveryReplayExit.INVALID_EVIDENCE, "the restored auth database's durable-recording state"
                    + " cannot be read, so the evidence cannot be anchored to the backup");
        }
        Set<UUID> trusted = set.entries().stream().map(ErasureLedgerEntry::authUserId).collect(Collectors.toSet());
        long missing = recorded.stream().filter(user -> !trusted.contains(user)).count();
        if (missing > 0) {
            throw refuse(RecoveryReplayExit.INVALID_EVIDENCE, "the trusted set lacks " + missing
                    + " erasure(s) the restored auth database marks DURABLY_RECORDED; the evidence is older than"
                    + " the backup");
        }
    }

    private static boolean flag(Environment environment, String property) {
        try {
            return environment.getProperty(property, Boolean.class, false);
        } catch (RuntimeException ex) {
            throw refuse(RecoveryReplayExit.REFUSED, property + " is not a boolean");
        }
    }

    private static Duration pollInterval(Environment environment) {
        Duration interval;
        try {
            interval = environment.getProperty(POLL_INTERVAL, Duration.class, DEFAULT_POLL_INTERVAL);
        } catch (RuntimeException ex) {
            throw refuse(RecoveryReplayExit.REFUSED, POLL_INTERVAL + " is not a duration");
        }
        if (interval == null || interval.isNegative() || interval.isZero()) {
            throw refuse(RecoveryReplayExit.REFUSED, POLL_INTERVAL + " must be positive");
        }
        return interval;
    }

    private static EvidenceTrust trust(Path path) {
        try {
            return EvidenceTrust.parse(read(path, "trust document"));
        } catch (DurableEvidenceException | IllegalArgumentException ex) {
            throw refuse(RecoveryReplayExit.INVALID_EVIDENCE, "trust document is invalid: " + ex.getMessage());
        }
    }

    private static byte[] read(Path path, String label) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException ex) {
            throw refuse(RecoveryReplayExit.REFUSED, label + " cannot be read");
        }
    }

    private static RecoveryReplayRefusal refuse(RecoveryReplayExit exit, String reason) {
        return new RecoveryReplayRefusal(exit, reason);
    }
}
