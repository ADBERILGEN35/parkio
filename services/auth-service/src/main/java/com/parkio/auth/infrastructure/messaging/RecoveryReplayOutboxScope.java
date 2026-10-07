package com.parkio.auth.infrastructure.messaging;

import com.parkio.auth.infrastructure.recovery.RecoveryReplayLaunch;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * What the outbox relay may publish in the recovery-replay command context (PR #295 review N11).
 * The restored auth database can hold unpublished outbox rows of the copied past, and the command
 * does not verify the broker, so publishing them could expose restored data before the replay is
 * COMPLETE. Under the profile the relay therefore publishes nothing until the command names its
 * attempt, and then only that attempt's {@code UserErasureRestoreReplayRequested} commands. Every
 * other unpublished row stays unpublished and untouched.
 */
@Component
@Profile(RecoveryReplayLaunch.PROFILE)
public class RecoveryReplayOutboxScope {

    private final AtomicReference<UUID> attempt = new AtomicReference<>();

    /** Called by the command just before it starts the replay of {@code recoveryAttemptId}. */
    public void limitTo(UUID recoveryAttemptId) {
        attempt.set(recoveryAttemptId);
    }

    /** The attempt whose replay commands may be published, if the command has named it yet. */
    public Optional<UUID> attempt() {
        return Optional.ofNullable(attempt.get());
    }
}
