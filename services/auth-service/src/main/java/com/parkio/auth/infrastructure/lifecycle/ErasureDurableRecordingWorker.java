package com.parkio.auth.infrastructure.lifecycle;

import com.parkio.auth.application.AccountErasureApplicationService;
import com.parkio.auth.application.ErasureDurableWorkerClaim;
import com.parkio.auth.application.port.DurableErasureRecordStore;
import com.parkio.auth.infrastructure.persistence.ErasureDurableWorkerRepository;
import com.parkio.auth.infrastructure.recovery.RecoveryReplayLaunch;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Default-off worker that retries {@code PENDING_DURABLE} persists and reconciles
 * {@code DURABLY_RECORDED} requests when participant ACKs are already present.
 *
 * <p>Store I/O runs outside database transactions. Row claims use a lease token so a stale
 * instance cannot overwrite a newer claim or terminal state.
 */
@Component
// Not in the recovery-replay command context (PR #295 review B5): a restored copy never writes
// into the real evidence store.
@Profile("!" + RecoveryReplayLaunch.PROFILE)
public class ErasureDurableRecordingWorker {

    private static final Logger log = LoggerFactory.getLogger(ErasureDurableRecordingWorker.class);

    private final AccountErasureApplicationService erasure;
    private final ErasureDurableWorkerRepository workerRepository;
    private final DurableErasureRecordStore durableStore;
    private final Clock clock;
    private final boolean workerEnabled;
    private final boolean durableRecordingEnabled;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration baseBackoff;
    private final Duration maxBackoff;
    private final Duration lease;

    public ErasureDurableRecordingWorker(
            AccountErasureApplicationService erasure,
            ErasureDurableWorkerRepository workerRepository,
            ObjectProvider<DurableErasureRecordStore> durableStores,
            Clock clock,
            @Value("${parkio.privacy.account-erasure.durable-recording-retry-worker-enabled:false}")
                    boolean workerEnabled,
            @Value("${parkio.privacy.account-erasure.durable-recording-enabled:false}")
                    boolean durableRecordingEnabled,
            @Value("${parkio.privacy.account-erasure.durable-recording-retry-worker-batch-size:20}")
                    int batchSize,
            @Value("${parkio.privacy.account-erasure.durable-recording-retry-worker-max-attempts:10}")
                    int maxAttempts,
            @Value("${parkio.privacy.account-erasure.durable-recording-retry-worker-base-backoff-ms:5000}")
                    long baseBackoffMs,
            @Value("${parkio.privacy.account-erasure.durable-recording-retry-worker-max-backoff-ms:900000}")
                    long maxBackoffMs,
            @Value("${parkio.privacy.account-erasure.durable-recording-retry-worker-lease-ms:120000}")
                    long leaseMs) {
        this.erasure = erasure;
        this.workerRepository = workerRepository;
        this.durableStore = durableStores.getIfAvailable();
        this.clock = clock;
        this.workerEnabled = workerEnabled;
        this.durableRecordingEnabled = durableRecordingEnabled;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.baseBackoff = Duration.ofMillis(baseBackoffMs);
        this.maxBackoff = Duration.ofMillis(maxBackoffMs);
        this.lease = Duration.ofMillis(leaseMs);
    }

    @Scheduled(
            fixedDelayString =
                    "${parkio.privacy.account-erasure.durable-recording-retry-worker-fixed-delay-ms:30000}")
    public void tick() {
        if (!workerEnabled || !durableRecordingEnabled || durableStore == null) {
            return;
        }
        Instant now = clock.instant();
        UUID claimToken = UUID.randomUUID();
        Instant claimExpires = now.plus(lease);
        List<ErasureDurableWorkerClaim> claims =
                workerRepository.claimBatch(now, batchSize, claimExpires, claimToken);
        for (ErasureDurableWorkerClaim claim : claims) {
            try {
                if ("PENDING_DURABLE".equals(claim.durableRecordingStatus())) {
                    erasure.processWorkerPersistClaim(claim, maxAttempts, baseBackoff, maxBackoff);
                } else if ("DURABLY_RECORDED".equals(claim.durableRecordingStatus())) {
                    erasure.processWorkerReconcileClaim(claim);
                } else {
                    workerRepository.releaseClaim(claim.requestId(), claim.claimToken(), now);
                }
            } catch (RuntimeException ex) {
                log.warn(
                        "durable-recording worker claim failed requestId={} token={}",
                        claim.requestId(),
                        claim.claimToken(),
                        ex);
            }
        }
    }
}
