package com.parkio.parking.application;

import com.parkio.parking.application.calibration.CalibrationProcessingResult;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Processes one continuous-calibration batch in its own transaction. */
@Component
public class ContinuousCalibrationRowProcessor {

    private static final int MIN_ATTEMPTS = 1;

    private final ContinuousCalibrationApplicationService service;
    private final int maxAttempts;
    private final TransactionTemplate transactions;

    public ContinuousCalibrationRowProcessor(
            ContinuousCalibrationApplicationService service,
            PlatformTransactionManager transactionManager,
            @Value("${parkio.lifecycle.calibration.max-attempts:3}") int maxAttempts) {
        this.service = Objects.requireNonNull(service, "service");
        this.maxAttempts = Math.max(MIN_ATTEMPTS, maxAttempts);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public CalibrationProcessingResult processTrustBatch(int limit) {
        return Objects.requireNonNull(transactions.execute(status -> service.processTrustBatch(limit)), "result");
    }

    public CalibrationProcessingResult processFraudBatch(int limit) {
        return Objects.requireNonNull(transactions.execute(status -> service.processFraudBatch(limit)), "result");
    }

    int maxAttempts() {
        return maxAttempts;
    }
}
