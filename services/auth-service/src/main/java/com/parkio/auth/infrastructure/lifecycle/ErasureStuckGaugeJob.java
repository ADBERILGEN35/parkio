package com.parkio.auth.infrastructure.lifecycle;

import com.parkio.auth.application.AccountErasureApplicationService;
import com.parkio.auth.infrastructure.metrics.ErasureMetrics;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
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
