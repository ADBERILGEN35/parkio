package com.parkio.auth.infrastructure.recovery;

/** A recovery-replay refusal with the exit code it maps to. The message is the verdict's reason. */
final class RecoveryReplayRefusal extends RuntimeException {

    private final RecoveryReplayExit exit;

    RecoveryReplayRefusal(RecoveryReplayExit exit, String reason) {
        super(reason);
        this.exit = exit;
    }

    RecoveryReplayExit exit() {
        return exit;
    }
}
