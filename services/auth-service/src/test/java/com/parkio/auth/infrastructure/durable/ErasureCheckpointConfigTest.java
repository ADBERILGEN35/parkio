package com.parkio.auth.infrastructure.durable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.parkio.auth.application.ErasureCheckpointProducer;
import com.parkio.auth.application.port.DurableErasureCheckpointStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The checkpoint producer is off by default and needs the object-lock store and valid timeouts
 * when enabled. That nothing calls it is guarded by checkpoint_producer_is_disabled (Python).
 */
class ErasureCheckpointConfigTest {

    private static final String PREFIX = "parkio.privacy.account-erasure.durable-store.checkpoint.";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ErasureCheckpointConfig.class)
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class));

    @Test
    void noProducerUnlessEnabled() {
        runner.withBean(DurableErasureCheckpointStore.class, () -> mock(DurableErasureCheckpointStore.class))
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(ErasureCheckpointProducer.class));
    }

    @Test
    void enabledWithoutTheObjectLockStoreFailsAtStartup() {
        runner.withPropertyValues(PREFIX + "enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining("durable-store.object-lock.enabled=true");
                });
    }

    @Test
    void enabledWithAZeroTimeoutFailsAtStartup() {
        runner.withBean(DurableErasureCheckpointStore.class, () -> mock(DurableErasureCheckpointStore.class))
                .withPropertyValues(PREFIX + "enabled=true", PREFIX + "lock-timeout=0s", PREFIX + "statement-timeout=500us")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining("lock-timeout")
                            .hasMessageContaining("statement-timeout");
                });
    }

    @Test
    void enabledWithTheStoreCreatesTheProducer() {
        runner.withBean(DurableErasureCheckpointStore.class, () -> mock(DurableErasureCheckpointStore.class))
                .withPropertyValues(PREFIX + "enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ErasureCheckpointProducer.class);
                    assertThat(context.getBean(ErasureCheckpointProperties.class).getLockTimeout()).hasSeconds(12);
                    assertThat(context.getBean(ErasureCheckpointProperties.class).getStatementTimeout()).hasSeconds(20);
                });
    }
}
