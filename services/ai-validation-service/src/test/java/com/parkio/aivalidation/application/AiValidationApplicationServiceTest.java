package com.parkio.aivalidation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.aivalidation.application.event.MediaUploadedEvent;
import com.parkio.aivalidation.application.event.ParkingSpotCreatedEvent;
import com.parkio.aivalidation.application.port.AiValidationResultRepository;
import com.parkio.aivalidation.application.port.InboxEventRepository;
import com.parkio.aivalidation.application.port.OutboxEventAppender;
import com.parkio.aivalidation.domain.AiValidationResult;
import com.parkio.aivalidation.domain.AiValidationStatus;
import com.parkio.aivalidation.domain.ContentRiskClassifier;
import com.parkio.aivalidation.domain.DeterministicAiValidator;
import com.parkio.aivalidation.domain.event.AiValidationCompletedEvent;
import com.parkio.aivalidation.domain.exception.AiValidationException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Behavioural unit tests for {@link AiValidationApplicationService} using in-memory
 * fake ports and the real (deterministic) placeholder validator — no Spring, no DB.
 */
class AiValidationApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-08T12:00:00Z");

    private FakeResultRepository results;
    private FakeInbox inbox;
    private FakeOutbox outbox;
    private AiValidationApplicationService service;
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        results = new FakeResultRepository();
        inbox = new FakeInbox();
        outbox = new FakeOutbox();
        transactionTemplate = new TransactionTemplate(new NoopTransactionManager());
        service = new AiValidationApplicationService(results, inbox, outbox,
                new DeterministicAiValidator(), Clock.fixed(NOW, ZoneOffset.UTC), transactionTemplate);
    }

    @Test
    void mediaUploadedEventCreatesValidationResult() {
        UUID mediaId = UUID.randomUUID();

        service.handleMediaUploaded(new MediaUploadedEvent(
                UUID.randomUUID(), mediaId, UUID.randomUUID(), "image/jpeg", 1024, "abc", NOW));

        assertThat(results.byId).hasSize(1);
        AiValidationResult result = results.byId.values().iterator().next();
        assertThat(result.mediaId()).isEqualTo(mediaId);
        assertThat(result.parkingSpotId()).isEmpty();
    }

    @Test
    void parkingSpotCreatedEventCreatesResultLinkedToSpot() {
        UUID mediaId = UUID.randomUUID();
        UUID spotId = UUID.randomUUID();

        service.handleParkingSpotCreated(new ParkingSpotCreatedEvent(
                UUID.randomUUID(), spotId, UUID.randomUUID(), mediaId, 41.0, 29.0, "ACTIVE", NOW));

        assertThat(results.byId).hasSize(1);
        AiValidationResult result = results.byId.values().iterator().next();
        assertThat(result.mediaId()).isEqualTo(mediaId);
        assertThat(result.parkingSpotId()).contains(spotId);
    }

    @Test
    void duplicateEventIsSkippedViaInbox() {
        MediaUploadedEvent event = new MediaUploadedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "image/png", 2048, "xyz", NOW);

        service.handleMediaUploaded(event);
        service.handleMediaUploaded(event); // redelivery

        assertThat(results.byId).hasSize(1);
    }

    @Test
    void completionEventIsEmittedWithDerivedStatus() {
        UUID mediaId = UUID.randomUUID();

        service.handleMediaUploaded(new MediaUploadedEvent(
                UUID.randomUUID(), mediaId, UUID.randomUUID(), "image/jpeg", 1024, "abc", NOW));

        assertThat(outbox.events).hasSize(1);
        AiValidationCompletedEvent emitted = outbox.events.get(0);
        assertThat(emitted.mediaId()).isEqualTo(mediaId);
        // Fail-closed default classifier → WARNING until a vision adapter is wired.
        assertThat(emitted.status()).isEqualTo(com.parkio.aivalidation.domain.AiValidationStatus.WARNING);
        assertThat(emitted.occurredAt()).isEqualTo(NOW);
    }

    @Test
    void resultStoresVehicleFitEstimatesAndFindings() {
        service.handleMediaUploaded(new MediaUploadedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "image/jpeg", 1024, "abc", NOW));

        AiValidationResult result = results.byId.values().iterator().next();
        assertThat(result.vehicleFitEstimates()).isNotEmpty();
        assertThat(result.findings()).isNotEmpty();
        assertThat(result.vehicleFitEstimates()).allSatisfy(v ->
                assertThat(v.fitScore()).isBetween(0, 100));
    }

    @Test
    void manualValidationStoresResultAndRecordsRequester() {
        UUID mediaId = UUID.randomUUID();
        UUID moderator = UUID.randomUUID();

        AiValidationResult result = service.createManualValidation(mediaId, null, moderator);

        assertThat(results.byId).containsKey(result.id());
        assertThat(result.requestedByUserId()).contains(moderator);
        assertThat(outbox.events).hasSize(1);
    }

    @Test
    void getByIdThrowsWhenMissing() {
        assertThatThrownBy(() -> service.getById(UUID.randomUUID()))
                .isInstanceOf(AiValidationException.class);
    }

    @Test
    void providerClassifyRunsOutsideActiveDatabaseTransaction() {
        AtomicBoolean classifySawActiveTx = new AtomicBoolean(true);
        AtomicInteger classifyCalls = new AtomicInteger();
        ContentRiskClassifier classifier = mediaId -> {
            classifyCalls.incrementAndGet();
            classifySawActiveTx.set(TransactionSynchronizationManager.isActualTransactionActive());
            return ContentRiskClassifier.Verdict.LIKELY_PARKING;
        };
        service = new AiValidationApplicationService(results, inbox, outbox,
                new DeterministicAiValidator(classifier), Clock.fixed(NOW, ZoneOffset.UTC),
                transactionTemplate);

        service.handleMediaUploaded(new MediaUploadedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "image/jpeg", 1024, "abc", NOW));

        assertThat(classifyCalls.get()).isEqualTo(1);
        assertThat(classifySawActiveTx.get()).isFalse();
        assertThat(results.byId).hasSize(1);
        assertThat(inbox.txDepthDuringClaim.get()).isGreaterThan(0);
    }

    @Test
    void revalidateInfrastructureFailurePersistsNewResult() {
        UUID mediaId = UUID.randomUUID();
        AiValidationResult saved = service.revalidateInfrastructureFailure(mediaId);
        assertThat(results.byId).containsKey(saved.id());
        assertThat(outbox.events).hasSize(1);
    }

    // --- fakes -------------------------------------------------------------

    private static final class FakeResultRepository implements AiValidationResultRepository {
        private final Map<UUID, AiValidationResult> byId = new HashMap<>();

        @Override
        public AiValidationResult save(AiValidationResult result) {
            byId.put(result.id(), result);
            return result;
        }

        @Override
        public Optional<AiValidationResult> findById(UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public List<AiValidationResult> findByMediaId(UUID mediaId) {
            return byId.values().stream().filter(r -> r.mediaId().equals(mediaId)).toList();
        }

        @Override
        public List<AiValidationResult> findByParkingSpotId(UUID parkingSpotId) {
            return byId.values().stream()
                    .filter(r -> r.parkingSpotId().map(parkingSpotId::equals).orElse(false))
                    .toList();
        }

        @Override
        public List<AiValidationResult> findByStatusAndCreatedAtBetween(
                AiValidationStatus status, Instant oldestInclusive, Instant newestExclusive, int limit) {
            return byId.values().stream()
                    .filter(r -> r.status() == status)
                    .filter(r -> !r.createdAt().isBefore(oldestInclusive)
                            && r.createdAt().isBefore(newestExclusive))
                    .limit(limit)
                    .toList();
        }
    }

    private static final class FakeInbox implements InboxEventRepository {
        private final java.util.Set<UUID> claimed = java.util.concurrent.ConcurrentHashMap.newKeySet();
        private final AtomicInteger txDepthDuringClaim = new AtomicInteger();

        @Override
        public boolean alreadyProcessed(UUID eventId) {
            return claimed.contains(eventId);
        }

        @Override
        public boolean tryClaim(UUID eventId, String eventType, Instant processedAt) {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                txDepthDuringClaim.incrementAndGet();
            }
            return claimed.add(eventId);
        }
    }

    private static final class FakeOutbox implements OutboxEventAppender {
        private final List<AiValidationCompletedEvent> events = new ArrayList<>();

        @Override
        public void append(AiValidationCompletedEvent event) {
            events.add(event);
        }
    }

    /** Minimal TM so TransactionTemplate marks synchronization active during Phase A/C. */
    private static final class NoopTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition)
                throws TransactionException {
            // no-op
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) throws TransactionException {
            // no-op
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) throws TransactionException {
            // no-op
        }
    }
}
