package com.parkio.parking.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.origin.Origin;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;

/**
 * #615: the İZUM body is read only until {@code max-response-time} after the request starts, so startup
 * refuses settings where {@code connect-timeout + read-timeout} reaches that bound. Without the check, an
 * operator who raises only the read timeout gets a first byte that arrives in time but fails on the time
 * bound, without a retry, classified {@code unknown}.
 */
class IzumTimingBoundsValidationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfig.class);

    @EnableConfigurationProperties(MunicipalSourceProperties.class)
    static class PropertiesConfig {
    }

    @Test
    void theDefaultsStart() {
        runner.run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "parkio.municipal.izum.read-timeout=40s",
            "parkio.municipal.izum.read-timeout=28s",
            "parkio.municipal.izum.connect-timeout=3s",
            "parkio.municipal.izum.max-response-time=29s",
            "parkio.municipal.izum.max-response-time=20s"
    })
    void startupRefusesConnectPlusReadTimeoutThatReachesMaxResponseTime(String setting) {
        runner.withPropertyValues(setting).run(context -> {
            assertThat(context).hasFailed();
            assertThat(fieldError(context.getStartupFailure()).getDefaultMessage())
                    .contains("must be less than max-response-time")
                    .contains("PARKIO_MUNICIPAL_IZUM_MAX_RESPONSE_TIME");
        });
    }

    @Test
    void raisingMaxResponseTimeTogetherWithTheReadTimeoutStarts() {
        runner.withPropertyValues(
                        "parkio.municipal.izum.read-timeout=40s",
                        "parkio.municipal.izum.max-response-time=45s")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void theErrorNamesTheValuesAndTheEnvironmentVariableThatSetThem() {
        runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource(
                                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                Map.of("PARKIO_MUNICIPAL_IZUM_READ_TIMEOUT", "40s"))))
                .run(context -> {
                    FieldError error = fieldError(context.getStartupFailure());
                    assertThat(error.getDefaultMessage()).startsWith(
                            "IZUM connect-timeout (2s) + read-timeout (40s) must be less than max-response-time (30s)");
                    assertThat(String.valueOf(Origin.from(error)))
                            .contains("PARKIO_MUNICIPAL_IZUM_READ_TIMEOUT");
                });
    }

    private static FieldError fieldError(Throwable startupFailure) {
        Throwable cause = startupFailure;
        while (cause != null && !(cause instanceof BindValidationException)) {
            cause = cause.getCause();
        }
        assertThat(cause).as("startup failure caused by a bind validation error").isNotNull();
        ObjectError error = ((BindValidationException) cause).getValidationErrors().getAllErrors().get(0);
        assertThat(error).isInstanceOf(FieldError.class);
        return (FieldError) error;
    }
}
