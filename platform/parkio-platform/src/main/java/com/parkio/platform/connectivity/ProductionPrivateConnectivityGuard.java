package com.parkio.platform.connectivity;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PP-01B-SPIKE-03 production-shaped private-connectivity guard.
 *
 * <p>Fails closed on plaintext / weak SSL / shared credentials / topology violations for
 * configurations that claim production posture. Local/dev and hosted-beta profiles are allowed
 * when explicitly classified as non-production.
 */
public final class ProductionPrivateConnectivityGuard {

    public enum Posture {
        PRODUCTION,
        HOSTED_BETA,
        LOCAL_DEV,
        UNKNOWN
    }

    public record Finding(Severity severity, String code, String detail) {}

    public enum Severity {
        ERROR,
        WARN,
        INFO
    }

    public record Evaluation(Posture posture, List<Finding> findings) {
        public boolean passed() {
            return findings.stream().noneMatch(f -> f.severity() == Severity.ERROR);
        }
    }

    public record ServiceDatasource(
            String service,
            String jdbcUrl,
            String username,
            String databaseName,
            String expectedCluster) {}

    private static final Pattern SSLMODE = Pattern.compile("(?i)(?:^|[?&])sslmode=([^&]*)");
    private static final Pattern PASSWORD_IN_URL = Pattern.compile("(?i)(password=|:[^/@]+@)");
    private static final Pattern IPV4_HOST =
            Pattern.compile("(?i)^jdbc:postgresql://(\\d{1,3}(?:\\.\\d{1,3}){3})(?::\\d+)?/");
    private static final Set<String> WEAK_SSL = Set.of("disable", "allow", "prefer");
    private static final Set<String> CORE_SERVICES = Set.of(
            "auth-service",
            "gateway-service",
            "user-service",
            "media-service",
            "gamification-service",
            "notification-service",
            "moderation-service",
            "analytics-service",
            "ai-validation-service");

    private ProductionPrivateConnectivityGuard() {}

    public static Evaluation evaluate(
            Posture posture, Collection<ServiceDatasource> datasources, boolean publicDbPortsExposed) {
        List<Finding> findings = new ArrayList<>();
        Objects.requireNonNull(posture, "posture");
        Objects.requireNonNull(datasources, "datasources");

        if (posture == Posture.LOCAL_DEV || posture == Posture.HOSTED_BETA) {
            findings.add(new Finding(
                    Severity.INFO,
                    "NON_PRODUCTION_POSTURE",
                    posture + " classified; production TLS/private rules not enforced as certification"));
            if (posture == Posture.HOSTED_BETA && publicDbPortsExposed) {
                findings.add(new Finding(
                        Severity.WARN,
                        "HOSTED_BETA_PUBLIC_PORTS",
                        "Hosted-beta exposes DB ports; must not be certified as production private posture"));
            }
            return new Evaluation(posture, List.copyOf(findings));
        }

        if (posture != Posture.PRODUCTION) {
            findings.add(new Finding(Severity.ERROR, "UNKNOWN_POSTURE", "Refuse UNKNOWN posture as production"));
            return new Evaluation(posture, List.copyOf(findings));
        }

        if (publicDbPortsExposed) {
            findings.add(new Finding(
                    Severity.ERROR,
                    "PUBLIC_DB_PORTS",
                    "Production Compose/IaC must not expose database ports publicly"));
        }

        Set<String> usernames = new LinkedHashSet<>();
        for (ServiceDatasource ds : datasources) {
            evaluateOne(ds, findings);
            if (ds.username() != null && !ds.username().isBlank()) {
                usernames.add(ds.username());
            }
        }

        if (datasources.size() >= 2 && usernames.size() == 1) {
            findings.add(new Finding(
                    Severity.ERROR,
                    "SHARED_APPLICATION_CREDENTIAL",
                    "All services share one database username; ADR requires isolated login roles"));
        }

        return new Evaluation(posture, List.copyOf(findings));
    }

    private static void evaluateOne(ServiceDatasource ds, List<Finding> findings) {
        String url = ds.jdbcUrl() == null ? "" : ds.jdbcUrl().trim();
        String service = ds.service() == null ? "unknown" : ds.service();

        if (url.isBlank()) {
            findings.add(new Finding(Severity.ERROR, "MISSING_JDBC_URL", service + " missing JDBC URL"));
            return;
        }
        if (!url.toLowerCase(Locale.ROOT).startsWith("jdbc:postgresql://")) {
            findings.add(new Finding(Severity.ERROR, "NON_POSTGRES_URL", service + " URL is not PostgreSQL JDBC"));
            return;
        }

        Matcher ip = IPV4_HOST.matcher(url);
        if (ip.find()) {
            findings.add(new Finding(
                    Severity.ERROR,
                    "HARDCODED_PUBLIC_OR_LITERAL_IP",
                    service + " uses IP literal host '" + ip.group(1) + "'; DNS FQDN required"));
        }

        if (PASSWORD_IN_URL.matcher(url).find() && url.toLowerCase(Locale.ROOT).contains("password=")) {
            findings.add(new Finding(
                    Severity.ERROR, "PASSWORD_IN_URL", service + " JDBC URL embeds a password"));
        }

        String sslmode = extractSslMode(url);
        if (sslmode == null || sslmode.isBlank()) {
            findings.add(new Finding(
                    Severity.ERROR,
                    "MISSING_SSLMODE",
                    service + " production URL missing sslmode (require verify-full)"));
        } else if (WEAK_SSL.contains(sslmode.toLowerCase(Locale.ROOT))) {
            findings.add(new Finding(
                    Severity.ERROR,
                    "WEAK_SSLMODE",
                    service + " sslmode=" + sslmode + " is forbidden in production"));
        } else if (!"verify-full".equalsIgnoreCase(sslmode) && !"verify-ca".equalsIgnoreCase(sslmode)) {
            if ("require".equalsIgnoreCase(sslmode)) {
                findings.add(new Finding(
                        Severity.ERROR,
                        "SSLMODE_REQUIRE_INSUFFICIENT",
                        service + " sslmode=require lacks server identity verification; use verify-full"));
            } else {
                findings.add(new Finding(
                        Severity.ERROR,
                        "UNEXPECTED_SSLMODE",
                        service + " sslmode=" + sslmode + " is not an approved production mode"));
            }
        } else if ("verify-ca".equalsIgnoreCase(sslmode)) {
            findings.add(new Finding(
                    Severity.WARN,
                    "SSLMODE_VERIFY_CA",
                    service + " verify-ca accepted only as staged exception; prefer verify-full"));
        }

        if ("parking-service".equals(service)) {
            if (!"parking".equalsIgnoreCase(nullToEmpty(ds.expectedCluster()))) {
                findings.add(new Finding(
                        Severity.ERROR,
                        "PARKING_WRONG_CLUSTER",
                        "parking-service must target parking/PostGIS cluster"));
            }
            String host = hostOf(url);
            if (host != null && host.toLowerCase(Locale.ROOT).contains("core")) {
                findings.add(new Finding(
                        Severity.ERROR,
                        "PARKING_POINTED_AT_CORE",
                        "parking-service hostname appears to reference core cluster"));
            }
        } else if (CORE_SERVICES.contains(service)) {
            if (!"core".equalsIgnoreCase(nullToEmpty(ds.expectedCluster()))) {
                findings.add(new Finding(
                        Severity.ERROR,
                        "CORE_WRONG_CLUSTER",
                        service + " must target core cluster"));
            }
        }
    }

    static String extractSslMode(String jdbcUrl) {
        Matcher m = SSLMODE.matcher(jdbcUrl);
        return m.find() ? m.group(1).trim() : null;
    }

    private static String hostOf(String jdbcUrl) {
        try {
            String stripped = jdbcUrl.substring("jdbc:".length());
            URI uri = URI.create(stripped);
            return uri.getHost();
        } catch (Exception ex) {
            return null;
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
