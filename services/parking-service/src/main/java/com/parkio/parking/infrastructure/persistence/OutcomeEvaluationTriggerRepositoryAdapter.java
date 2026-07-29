package com.parkio.parking.infrastructure.persistence;

import com.parkio.parking.application.port.OutcomeEvaluationTriggerPort;
import com.parkio.parking.outcome.history.OutcomeEvaluationTrigger;
import com.parkio.parking.application.outcome.OutcomeEvaluationTriggerRequest;
import com.parkio.parking.infrastructure.persistence.entity.OutcomeEvaluationTriggerEntity;
import com.parkio.parking.infrastructure.persistence.jpa.OutcomeEvaluationTriggerJpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

@Component
public class OutcomeEvaluationTriggerRepositoryAdapter implements OutcomeEvaluationTriggerPort {

    private final OutcomeEvaluationTriggerJpaRepository jpa;
    private final int maxAttempts;

    public OutcomeEvaluationTriggerRepositoryAdapter(
            OutcomeEvaluationTriggerJpaRepository jpa,
            @Value("${parkio.parking.outcome.max-processing-attempts:10}") int maxAttempts) {
        this.jpa = jpa;
        this.maxAttempts = maxAttempts;
    }

    @Override
    public void enqueue(OutcomeEvaluationTriggerRequest trigger) {
        try {
            jpa.save(new OutcomeEvaluationTriggerEntity(
                    UUID.nameUUIDFromBytes(("outcome-trigger|" + trigger.evaluationId()).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    trigger.evaluationId(),
                    trigger.parkingSpotId(),
                    trigger.triggerType().name(),
                    trigger.triggerReference(),
                    trigger.evidenceCutoffAt(),
                    false,
                    null,
                    0,
                    null,
                    null,
                    false,
                    trigger.createdAt()));
        } catch (DataIntegrityViolationException ex) {
            // Deterministic duplicate trigger append; safe no-op.
        }
    }

    @Override
    public List<OutcomeEvaluationTriggerRequest> claimPendingBatch(int limit) {
        return jpa.claimPendingBatch(limit).stream().map(this::toRequest).toList();
    }

    @Override
    public void markProcessed(OutcomeEvaluationTriggerRequest trigger, Instant processedAt) {
        OutcomeEvaluationTriggerEntity entity = jpa.findById(triggerEntityId(trigger.evaluationId())).orElseThrow();
        entity.markProcessed(processedAt);
    }

    @Override
    public void recordFailure(OutcomeEvaluationTriggerRequest trigger, String failureStage, Instant failedAt) {
        OutcomeEvaluationTriggerEntity entity = jpa.findById(triggerEntityId(trigger.evaluationId())).orElseThrow();
        entity.recordFailure(failureStage, failedAt, maxAttempts);
    }

    private OutcomeEvaluationTriggerRequest toRequest(OutcomeEvaluationTriggerEntity entity) {
        return new OutcomeEvaluationTriggerRequest(
                entity.getEvaluationId(),
                entity.getParkingSpotId(),
                OutcomeEvaluationTrigger.valueOf(entity.getTriggerType()),
                entity.getTriggerReference(),
                entity.getEvidenceCutoffAt(),
                entity.getCreatedAt());
    }

    private UUID triggerEntityId(UUID evaluationId) {
        return UUID.nameUUIDFromBytes(("outcome-trigger|" + evaluationId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}