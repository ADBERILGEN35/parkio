package com.parkio.auth.infrastructure.durable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code parkio.privacy.account-erasure.durable-store.checkpoint.*}. Off by default. The
 * timeouts bound the SHARE-lock capture (defaults from the #104 locked-snapshot SQL). There is
 * no cadence setting: when and how often checkpoints run is an operator decision.
 */
@ConfigurationProperties(prefix = "parkio.privacy.account-erasure.durable-store.checkpoint")
public class ErasureCheckpointProperties {

    private boolean enabled;
    private Duration lockTimeout = Duration.ofSeconds(12);
    private Duration statementTimeout = Duration.ofSeconds(20);

    /** Names of invalid settings; values are never included. */
    List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (lockTimeout == null || lockTimeout.toMillis() < 1) {
            problems.add("lock-timeout (at least 1ms)");
        }
        if (statementTimeout == null || statementTimeout.toMillis() < 1) {
            problems.add("statement-timeout (at least 1ms)");
        }
        return problems;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getLockTimeout() {
        return lockTimeout;
    }

    public void setLockTimeout(Duration lockTimeout) {
        this.lockTimeout = lockTimeout;
    }

    public Duration getStatementTimeout() {
        return statementTimeout;
    }

    public void setStatementTimeout(Duration statementTimeout) {
        this.statementTimeout = statementTimeout;
    }
}
