package com.parkio.parking.application;

import com.parkio.parking.application.trust.TrustShadowProcessingResult;
import com.parkio.parking.application.trust.TrustShadowFailureStage;
import com.parkio.parking.application.trust.ValidatedOutcomeForTrust;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Processes one trust-shadow candidate in its own transaction. */
@Component
public class TrustShadowRowProcessor {

    private static final int MIN_ATTEMPTS = 1;

    private final TrustShadowApplicationService service;
    private final int maxAttempts;
    private final TransactionTemplate transactions;

    public TrustShadowRowProcessor(
            TrustShadowApplicationService service,
            PlatformTransactionManager transactionManager,
            @Value("${parkio.lifecycle.trust-shadow.max-attempts:3}") int maxAttempts) {
        this.service = Objects.requireNonNull(service, "service");
        this.maxAttempts = Math.max(MIN_ATTEMPTS, maxAttempts);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public TrustShadowProcessingResult process(ValidatedOutcomeForTrust candidate) {
        TrustShadowProcessingResult last = null;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            last = transactions.execute(status -> {
                TrustShadowProcessingResult result = service.process(candidate);
                if (result.status() == TrustShadowProcessingResult.Status.FAILED
                        && result.failureStage().orElse(null) == TrustShadowFailureStage.SNAPSHOT_CONFLICT) {
                    status.setRollbackOnly();
                }
                return result;
            });
            if (last.status() != TrustShadowProcessingResult.Status.FAILED
                    || last.failureStage().orElse(null) != TrustShadowFailureStage.SNAPSHOT_CONFLICT) {
                return last;
            }
        }
        return Objects.requireNonNull(last, "last");
    }
}

