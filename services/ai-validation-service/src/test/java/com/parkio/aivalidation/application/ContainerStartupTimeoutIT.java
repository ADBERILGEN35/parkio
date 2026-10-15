package com.parkio.aivalidation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.containers.ContainerLaunchException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * N-19 / #368: controlled slow-start fixture for Testcontainers {@code LogMessageWaitStrategy}
 * timeouts. Demonstrates failure under a short startup timeout and success with the same
 * three-minute allowance applied to {@link AccountErasureAckOutboxPostgresIT} Postgres/Kafka.
 *
 * <p>Uses a synthetic Alpine readiness log only (no Spring context, no product data).
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class ContainerStartupTimeoutIT {

    private static final DockerImageName ALPINE = DockerImageName.parse("alpine:3.20");
    private static final Duration SHORT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration ALLOWED_TIMEOUT = Duration.ofMinutes(3);
    private static final int READY_DELAY_SECONDS = 8;

    @Test
    void shortStartupTimeoutFailsWhenReadinessLogIsDelayed() {
        try (GenericContainer<?> container = delayedReadyContainer(SHORT_TIMEOUT)) {
            assertThatThrownBy(container::start)
                    .isInstanceOf(ContainerLaunchException.class)
                    .hasStackTraceContaining("Timed out waiting for log output matching");
            assertThat(container.isRunning()).isFalse();
        }
    }

    @Test
    void threeMinuteStartupTimeoutAllowsDelayedReadinessLog() {
        try (GenericContainer<?> container = delayedReadyContainer(ALLOWED_TIMEOUT)) {
            long started = System.nanoTime();
            assertThatCode(container::start).doesNotThrowAnyException();
            assertThat(container.isRunning()).isTrue();
            org.slf4j.LoggerFactory.getLogger(ContainerStartupTimeoutIT.class)
                    .info(
                            "testcontainers lifecycle role=slow-start-fixture image={} running={} elapsedMs={} startupTimeoutSec={}",
                            container.getDockerImageName(),
                            container.isRunning(),
                            (System.nanoTime() - started) / 1_000_000L,
                            ALLOWED_TIMEOUT.toSeconds());
        }
    }

    /**
     * Delays the readiness log past {@link #SHORT_TIMEOUT} but well under {@link #ALLOWED_TIMEOUT},
     * mirroring the CI failure mode ({@code LogMessageWaitStrategy} / ContainerLaunchException).
     */
    private static GenericContainer<?> delayedReadyContainer(Duration startupTimeout) {
        return new GenericContainer<>(ALPINE)
                .withCommand(
                        "sh",
                        "-c",
                        "sleep " + READY_DELAY_SECONDS + "; echo PARKIO_N19_READY; sleep infinity")
                .waitingFor(Wait.forLogMessage(".*PARKIO_N19_READY.*", 1).withStartupTimeout(startupTimeout))
                .withStartupTimeout(startupTimeout);
    }
}
