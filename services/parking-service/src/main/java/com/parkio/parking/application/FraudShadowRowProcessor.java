package com.parkio.parking.application;

import com.parkio.parking.application.fraud.FraudShadowFailureStage;
import com.parkio.parking.application.fraud.FraudShadowProcessingResult;
import com.parkio.parking.application.fraud.ValidatedOutcomeForFraud;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Processes one fraud-shadow candidate in its own transaction. */
@Component
public class FraudShadowRowProcessor {

    private static final int MIN_ATTEMPTS = 1;

    private final FraudShadowApplicationService service;
    private final int maxAttempts;
    private final TransactionTemplate transactions;

    public FraudShadowRowProcessor(
            FraudShadowApplicationService service,
            PlatformTransactionManager transactionManager,
            @Value("${parkio.lifecycle.fraud-shadow.max-attempts:3}") int maxAttempts) {
        this.service = Objects.requireNonNull(service, "service");
        this.maxAttempts = Math.max(MIN_ATTEMPTS, maxAttempts);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public FraudShadowProcessingResult process(ValidatedOutcomeForFraud candidate) {
        return Objects.requireNonNull(transactions.execute(status -> service.process(candidate)), "result");
    }
}
