package com.parkio.parking.application;

import com.parkio.parking.application.reward.RewardShadowProcessingResult;
import com.parkio.parking.application.reward.ValidatedOutcomeForReward;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Processes one reward-shadow candidate in its own transaction. */
@Component
public class RewardShadowRowProcessor {

    private final RewardShadowApplicationService service;
    private final TransactionTemplate transactions;

    public RewardShadowRowProcessor(
            RewardShadowApplicationService service,
            PlatformTransactionManager transactionManager) {
        this.service = Objects.requireNonNull(service, "service");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public RewardShadowProcessingResult process(ValidatedOutcomeForReward candidate) {
        return Objects.requireNonNull(transactions.execute(status -> service.process(candidate)), "result");
    }
}
