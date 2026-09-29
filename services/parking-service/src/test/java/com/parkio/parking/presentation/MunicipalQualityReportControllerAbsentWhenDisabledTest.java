package com.parkio.parking.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.parkio.parking.application.quality.MunicipalQualityReportService;
import com.parkio.parking.infrastructure.metrics.MunicipalQualityReportMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import com.parkio.platform.api.ApiError;

/**
 * DATA-WP-15 kill-switch: {@code parkio.municipal.ops.quality-report-enabled} gates bean
 * registration, so the whole path disappears when the flag is off. A standalone MockMvc setup
 * cannot evaluate {@code @ConditionalOnProperty}, so the condition is exercised against a real
 * (but minimal) application context and the disabled-path 404 mapping is asserted on the
 * handler that serves it.
 */
class MunicipalQualityReportControllerAbsentWhenDisabledTest {
    private static final String FLAG = "parkio.municipal.ops.quality-report-enabled";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(MunicipalQualityReportService.class, () -> mock(MunicipalQualityReportService.class))
            .withBean(MunicipalQualityReportMetrics.class,
                    () -> new MunicipalQualityReportMetrics(new SimpleMeterRegistry()))
            .withUserConfiguration(MunicipalQualityReportController.class);

    @Test
    void controllerIsAbsentWhenTheFlagIsMissing() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(MunicipalQualityReportController.class);
        });
    }

    @Test
    void controllerIsAbsentWhenTheFlagIsFalse() {
        runner.withPropertyValues(FLAG + "=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(MunicipalQualityReportController.class);
        });
    }

    @Test
    void controllerIsAbsentForNonTrueValues() {
        for (String value : new String[] {"", "1", "yes", "TRUEISH", "on"}) {
            runner.withPropertyValues(FLAG + "=" + value).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(MunicipalQualityReportController.class);
            });
        }
    }

    @Test
    void controllerIsRegisteredWhenTheFlagIsTrue() {
        runner.withPropertyValues(FLAG + "=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(MunicipalQualityReportController.class);
        });
        runner.withPropertyValues(FLAG + "=TRUE").run(context ->
                assertThat(context).hasSingleBean(MunicipalQualityReportController.class));
    }

    @Test
    void conditionIsDeclaredOnTheControllerWithoutMatchIfMissing() {
        ConditionalOnProperty condition =
                MunicipalQualityReportController.class.getAnnotation(ConditionalOnProperty.class);
        assertThat(condition).isNotNull();
        assertThat(condition.name()).containsExactly(FLAG);
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isFalse();
    }

    @Test
    void disabledPathIsMappedToA404WithoutLeakingTheReason() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler(
                Clock.fixed(Instant.parse("2026-08-03T10:00:00Z"), ZoneOffset.UTC));

        ResponseEntity<ApiError> response = handler.handleNoResource(
                new NoResourceFoundException(
                        org.springframework.http.HttpMethod.GET,
                        "/api/v1/parking/admin/municipal/quality-report"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("NOT_FOUND");
        assertThat(response.getBody().message()).isEqualTo("Resource not found.");
        assertThat(response.getBody().message()).doesNotContain("quality-report");
    }
}
