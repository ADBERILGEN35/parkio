package com.parkio.auth.infrastructure.durable;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The object-lock store is off by default and refuses to start half-configured. */
class ObjectLockDurableStoreConfigTest {

    private static final String PREFIX = "parkio.privacy.account-erasure.durable-store.object-lock.";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ObjectLockDurableStoreConfig.class)
            .withBean(Clock.class, Clock::systemUTC);

    @Test
    void noStoreUnlessEnabled() {
        runner.run(context -> assertThat(context)
                .hasNotFailed()
                .doesNotHaveBean(ObjectLockDurableErasureRecordStore.class));
    }

    @Test
    void enabledWithoutOperatorInputsFailsNamingOnlyTheSettings() {
        runner.withPropertyValues(
                        PREFIX + "enabled=true",
                        PREFIX + "secret-key=do-not-print-this-secret",
                        PREFIX + "producer-key=do-not-print-this-key",
                        PREFIX + "retention-mode=forever")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining("endpoint")
                            .hasMessageContaining("bucket")
                            .hasMessageContaining("access-key")
                            .hasMessageContaining("retention-mode")
                            .hasMessageContaining("retention (positive duration)")
                            .hasMessageContaining("database-identity")
                            .hasMessageContaining("producer-id")
                            .hasMessageNotContaining("do-not-print-this-secret")
                            .hasMessageNotContaining("do-not-print-this-key");
                });
    }
}
