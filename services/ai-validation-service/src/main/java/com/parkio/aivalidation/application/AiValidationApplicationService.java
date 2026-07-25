package com.parkio.aivalidation.application;

import com.parkio.aivalidation.application.event.MediaUploadedEvent;
import com.parkio.aivalidation.application.event.ParkingSpotCreatedEvent;
import com.parkio.aivalidation.application.event.ParkingSpotModerationRetryRequestedEvent;
import com.parkio.aivalidation.application.port.AiValidationResultRepository;
import com.parkio.aivalidation.application.port.InboxEventRepository;
import com.parkio.aivalidation.application.port.OutboxEventAppender;
import com.parkio.aivalidation.domain.AiValidationResult;
import com.parkio.aivalidation.domain.DeterministicAiValidator;
import com.parkio.aivalidation.domain.event.AiValidationCompletedEvent;
import com.parkio.aivalidation.domain.exception.AiValidationErrorCode;
import com.parkio.aivalidation.domain.exception.AiValidationException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Advisory validation use cases: react to upstream events (idempotently) and serve
 * manual validation requests, persisting a result and emitting an advisory event.
 *
 * <p>Transaction boundary (production-critical): provider I/O (media fetch, Gemini HTTP,
 * retry sleeps, image decode) runs <strong>outside</strong> any database transaction.
 * Short transactions cover only (1) inbox existence checks and (2) claim + persist +
 * outbox append. Claim is performed in the same short transaction as persist so a crash
 * after I/O but before commit allows Kafka redelivery.
 *
 * <p>ai-validation-service remains an <strong>advisor</strong> toward moderation
 * (ai-context/02): it does not mutate parking rows directly. Parking-service consumes
 * {@link AiValidationCompletedEvent} and enforces the publication gate.
 */
@Service
public class AiValidationApplicationService {

    private final AiValidationResultRepository results;
    private final InboxEventRepository inbox;
    private final OutboxEventAppender outbox;
    private final DeterministicAiValidator validator;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public AiValidationApplicationService(AiValidationResultRepository results,
                                          InboxEventRepository inbox,
                                          OutboxEventAppender outbox,
                                          DeterministicAiValidator validator,
                                          Clock clock,
                                          TransactionTemplate transactionTemplate) {
        this.results = results;
        this.inbox = inbox;
        this.outbox = outbox;
        this.validator = validator;
        this.clock = clock;
        this.transactionTemplate = transactionTemplate;
    }

    /** A newly uploaded media object is analysed for an advisory result. */
    public void handleMediaUploaded(MediaUploadedEvent event) {
        processEvent(event.eventId(), "MediaUploaded", event.mediaId(), null, null);
    }

    /** A newly created spot's photo is analysed; the result is linked to the spot. */
    public void handleParkingSpotCreated(ParkingSpotCreatedEvent event) {
        processEvent(event.eventId(), "ParkingSpotCreated",
                event.mediaId(), event.parkingSpotId(), null);
    }

    /**
     * Parking-service's publication gate went unanswered and asked for a bounded re-run.
     * Processed exactly like a creation: the retry carries its own {@code eventId}, so the
     * inbox admits it as new work rather than deduplicating it against the original.
     */
    public void handleModerationRetryRequested(ParkingSpotModerationRetryRequestedEvent event) {
        processEvent(event.eventId(), "ParkingSpotModerationRetryRequested",
                event.mediaId(), event.parkingSpotId(), null);
    }

    /** Runs a manual placeholder validation requested by a moderator/admin. */
    public AiValidationResult createManualValidation(UUID mediaId, UUID parkingSpotId, UUID requestedByUserId) {
        Instant now = clock.instant();
        // Provider I/O outside any transaction.
        AiValidationResult result = validator.validate(mediaId, parkingSpotId, requestedByUserId, now);
        return transactionTemplate.execute(status -> {
            AiValidationResult saved = results.save(result);
            outbox.append(AiValidationCompletedEvent.of(saved, now));
            return saved;
        });
    }

    public AiValidationResult getById(UUID validationId) {
        return results.findById(validationId)
                .orElseThrow(() -> new AiValidationException(AiValidationErrorCode.VALIDATION_RESULT_NOT_FOUND));
    }

    public List<AiValidationResult> getByMediaId(UUID mediaId) {
        return results.findByMediaId(mediaId);
    }

    public List<AiValidationResult> getByParkingSpotId(UUID parkingSpotId) {
        return results.findByParkingSpotId(parkingSpotId);
    }

    /**
     * Operator / scheduled recovery: re-runs classification for media that previously
     * failed closed on infrastructure. Does not claim an inbox event; persists a new
     * result + outbox. Conclusive reuse / semantic-UNCERTAIN TTL still apply inside
     * the classifier.
     */
    public AiValidationResult revalidateInfrastructureFailure(UUID mediaId) {
        Instant now = clock.instant();
        AiValidationResult result = validator.validate(mediaId, null, null, now);
        return transactionTemplate.execute(status -> {
            AiValidationResult saved = results.save(result);
            outbox.append(AiValidationCompletedEvent.of(saved, now));
            return saved;
        });
    }

    /**
     * Phase A (short TX): skip if already processed.
     * Phase B (no TX): validator / classifier / provider I/O.
     * Phase C (short TX): claim + save + outbox.
     */
    private void processEvent(UUID eventId, String eventType, UUID mediaId,
                              UUID parkingSpotId, UUID requestedByUserId) {
        Boolean alreadyDone = transactionTemplate.execute(status -> inbox.alreadyProcessed(eventId));
        if (Boolean.TRUE.equals(alreadyDone)) {
            return;
        }

        Instant now = clock.instant();
        // Phase B — must not hold a DB connection / transaction.
        AiValidationResult result = validator.validate(mediaId, parkingSpotId, requestedByUserId, now);

        transactionTemplate.executeWithoutResult(status -> {
            if (!inbox.tryClaim(eventId, eventType, now)) {
                // Another worker claimed concurrently after Phase A — discard our result.
                return;
            }
            AiValidationResult saved = results.save(result);
            outbox.append(AiValidationCompletedEvent.of(saved, now));
        });
    }
}
