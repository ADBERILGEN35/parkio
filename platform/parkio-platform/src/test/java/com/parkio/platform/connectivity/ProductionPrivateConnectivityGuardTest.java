package com.parkio.platform.connectivity;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.platform.connectivity.ProductionPrivateConnectivityGuard.Evaluation;
import com.parkio.platform.connectivity.ProductionPrivateConnectivityGuard.Posture;
import com.parkio.platform.connectivity.ProductionPrivateConnectivityGuard.ServiceDatasource;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductionPrivateConnectivityGuardTest {

    @Test
    void productionRejectsPlaintextAndWeakSslModes() {
        List<ServiceDatasource> bad = List.of(
                ds("auth-service", "jdbc:postgresql://db.example:5432/parkio_auth?sslmode=disable", "parkio_auth", "core"),
                ds(
                        "user-service",
                        "jdbc:postgresql://db.example:5432/parkio_user?sslmode=prefer",
                        "parkio_user",
                        "core"));

        Evaluation eval = ProductionPrivateConnectivityGuard.evaluate(Posture.PRODUCTION, bad, false);
        assertThat(eval.passed()).isFalse();
        assertThat(eval.findings())
                .extracting(f -> f.code())
                .contains("WEAK_SSLMODE");
    }

    @Test
    void productionRejectsIpLiteralMissingSslSharedCredentialAndParkingOnCore() {
        List<ServiceDatasource> bad = List.of(
                ds(
                        "auth-service",
                        "jdbc:postgresql://10.0.0.5:5432/parkio_auth?sslmode=require&password=secret",
                        "shared",
                        "core"),
                ds(
                        "parking-service",
                        "jdbc:postgresql://parkio-core-pg.postgres.database.azure.com:5432/parkio_parking?sslmode=verify-full",
                        "shared",
                        "core"));

        Evaluation eval = ProductionPrivateConnectivityGuard.evaluate(Posture.PRODUCTION, bad, true);
        assertThat(eval.passed()).isFalse();
        assertThat(eval.findings())
                .extracting(f -> f.code())
                .contains(
                        "HARDCODED_PUBLIC_OR_LITERAL_IP",
                        "PASSWORD_IN_URL",
                        "SSLMODE_REQUIRE_INSUFFICIENT",
                        "SHARED_APPLICATION_CREDENTIAL",
                        "PARKING_WRONG_CLUSTER",
                        "PARKING_POINTED_AT_CORE",
                        "PUBLIC_DB_PORTS");
    }

    @Test
    void productionAcceptsTwoClusterVerifyFullPrivateFqdnPlaceholders() {
        List<ServiceDatasource> ok = List.of(
                ds(
                        "auth-service",
                        "jdbc:postgresql://parkio-core-pg.private.example:5432/parkio_auth?sslmode=verify-full",
                        "parkio_auth",
                        "core"),
                ds(
                        "gateway-service",
                        "jdbc:postgresql://parkio-core-pg.private.example:5432/parkio_gateway?sslmode=verify-full",
                        "parkio_gateway",
                        "core"),
                ds(
                        "user-service",
                        "jdbc:postgresql://parkio-core-pg.private.example:5432/parkio_user?sslmode=verify-full",
                        "parkio_user",
                        "core"),
                ds(
                        "media-service",
                        "jdbc:postgresql://parkio-core-pg.private.example:5432/parkio_media?sslmode=verify-full",
                        "parkio_media",
                        "core"),
                ds(
                        "gamification-service",
                        "jdbc:postgresql://parkio-core-pg.private.example:5432/parkio_gamification?sslmode=verify-full",
                        "parkio_gamification",
                        "core"),
                ds(
                        "notification-service",
                        "jdbc:postgresql://parkio-core-pg.private.example:5432/parkio_notification?sslmode=verify-full",
                        "parkio_notification",
                        "core"),
                ds(
                        "moderation-service",
                        "jdbc:postgresql://parkio-core-pg.private.example:5432/parkio_moderation?sslmode=verify-full",
                        "parkio_moderation",
                        "core"),
                ds(
                        "analytics-service",
                        "jdbc:postgresql://parkio-core-pg.private.example:5432/parkio_analytics?sslmode=verify-full",
                        "parkio_analytics",
                        "core"),
                ds(
                        "ai-validation-service",
                        "jdbc:postgresql://parkio-core-pg.private.example:5432/parkio_aivalidation?sslmode=verify-full",
                        "parkio_aivalidation",
                        "core"),
                ds(
                        "parking-service",
                        "jdbc:postgresql://parkio-parking-pg.private.example:5432/parkio_parking?sslmode=verify-full",
                        "parkio_parking",
                        "parking"));

        Evaluation eval = ProductionPrivateConnectivityGuard.evaluate(Posture.PRODUCTION, ok, false);
        assertThat(eval.passed()).isTrue();
    }

    @Test
    void localDevAndHostedBetaAreNotFalselyCertifiedAsProduction() {
        List<ServiceDatasource> local = List.of(
                ds("auth-service", "jdbc:postgresql://localhost:5432/parkio_auth", "parkio_auth", "core"));

        Evaluation localEval = ProductionPrivateConnectivityGuard.evaluate(Posture.LOCAL_DEV, local, true);
        assertThat(localEval.passed()).isTrue();
        assertThat(localEval.findings()).extracting(f -> f.code()).contains("NON_PRODUCTION_POSTURE");

        Evaluation beta = ProductionPrivateConnectivityGuard.evaluate(Posture.HOSTED_BETA, local, true);
        assertThat(beta.passed()).isTrue();
        assertThat(beta.findings()).extracting(f -> f.code()).contains("HOSTED_BETA_PUBLIC_PORTS");
    }

    private static ServiceDatasource ds(String service, String url, String user, String cluster) {
        String db = url.contains("/") ? url.substring(url.lastIndexOf('/') + 1).replaceAll("\\?.*", "") : "";
        return new ServiceDatasource(service, url, user, db, cluster);
    }
}
