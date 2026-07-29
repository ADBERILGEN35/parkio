package com.parkio.parking.infrastructure.lifecycle;

import com.parkio.parking.application.LegacyPolicyResetApplicationService;
import com.parkio.parking.application.LegacyPolicyResetApplicationService.LegacyPolicyResetReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Opt-in one-shot operator runner for the legacy policy reset. Disabled by default.
 * Prefer dry-run first; never enable on routine deploy.
 */
@Component
@ConditionalOnProperty(name = "parkio.parking.legacy-policy-reset.enabled", havingValue = "true")
public class LegacyPolicyResetRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LegacyPolicyResetRunner.class);

    private final LegacyPolicyResetApplicationService service;
    private final boolean dryRun;
    private final String targetPolicyVersion;
    private final int batchSize;

    public LegacyPolicyResetRunner(
            LegacyPolicyResetApplicationService service,
            @Value("${parkio.parking.legacy-policy-reset.dry-run:true}") boolean dryRun,
            @Value("${parkio.parking.legacy-policy-reset.target-policy-version:2026-07-photo-policy-v3-recall}")
                    String targetPolicyVersion,
            @Value("${parkio.parking.legacy-policy-reset.batch-size:500}") int batchSize) {
        this.service = service;
        this.dryRun = dryRun;
        this.targetPolicyVersion = targetPolicyVersion;
        this.batchSize = batchSize;
    }

    @Override
    public void run(ApplicationArguments args) {
        LegacyPolicyResetReport report = dryRun
                ? service.dryRun(targetPolicyVersion, batchSize)
                : service.execute(targetPolicyVersion, batchSize);
        log.info(
                "Legacy policy reset {} target={} eligible={} updated={} failed={} "
                        + "skippedRejected={} skippedTerminal={} skippedNewPolicy={} "
                        + "statusBreakdown={} policyBreakdown={}",
                report.dryRun() ? "DRY_RUN" : "EXECUTE",
                report.targetPolicyVersion(),
                report.eligibleCount(),
                report.updatedCount(),
                report.failedCount(),
                report.skippedAlreadyRejected(),
                report.skippedOtherTerminal(),
                report.skippedNewPolicy(),
                report.statusBreakdown(),
                report.policyVersionBreakdown());
    }
}
