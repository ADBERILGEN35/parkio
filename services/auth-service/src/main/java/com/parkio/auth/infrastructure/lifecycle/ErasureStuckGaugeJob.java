package com.parkio.auth.infrastructure.lifecycle;

import com.parkio.auth.application.AccountErasureApplicationService;
import com.parkio.auth.infrastructure.metrics.ErasureMetrics;
import com.parkio.auth.infrastructure.recovery.RecoveryReplayLaunch;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
// Not in the recovery-replay command context (PR #295 review B5): no scheduler the replay does not need.
@Profile("!" + RecoveryReplayLaunch.PROFILE)
public class ErasureStuckGaugeJob {

    private final AccountErasureApplicationService erasure;
    private final ErasureMetrics metrics;

    public ErasureStuckGaugeJob(AccountErasureApplicationService erasure, ErasureMetrics metrics) {
        this.erasure = erasure;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${parkio.privacy.account-erasure.stuck-poll-ms:60000}")
    public void refresh() {
        metrics.setStuck(erasure.stuckCount(metrics.sla()));
    }
}
