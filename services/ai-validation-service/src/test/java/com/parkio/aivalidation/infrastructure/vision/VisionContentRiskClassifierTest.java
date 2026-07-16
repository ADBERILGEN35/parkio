package com.parkio.aivalidation.infrastructure.vision;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.aivalidation.application.port.AiValidationResultRepository;
import com.parkio.aivalidation.domain.AiValidationResult;
import com.parkio.aivalidation.domain.ContentRiskClassifier;
import com.parkio.aivalidation.domain.ContentRiskClassifier.Verdict;
import com.parkio.aivalidation.domain.DeterministicAiValidator;
import com.parkio.aivalidation.infrastructure.config.VisionProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the fail-closed vision classifier: confidence thresholds, every
 * failure mode resolving to UNCERTAIN (never LIKELY_PARKING), and provider-call
 * dedup against persisted conclusive results.
 */
class VisionContentRiskClassifierTest {

    private static final Instant NOW = Instant.parse("2026-07-15T12:00:00Z");
    private static final byte[] JPEG = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3};

    private FakeFetcher fetcher;
    private FakeProvider provider;
    private FakeResults results;
    private VisionProperties properties;
    private SimpleMeterRegistry registry;
    private VisionContentRiskClassifier classifier;

    @BeforeEach
    void setUp() {
        fetcher = new FakeFetcher();
        provider = new FakeProvider();
        results = new FakeResults();
        properties = new VisionProperties();
        registry = new SimpleMeterRegistry();
        classifier = new VisionContentRiskClassifier(fetcher, provider, results, properties,
                new VisionMetrics(registry, "gemini"), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // --- verdict + confidence policy ---------------------------------------

    @Test
    void confidentLikelyParkingStands() {
        provider.analysis = new VisionProviderClient.VisionAnalysis("LIKELY_PARKING", 0.92, "EMPTY_SPACE_VISIBLE");
        assertThat(classifier.classify(UUID.randomUUID())).isEqualTo(Verdict.LIKELY_PARKING);
        assertThat(provider.calls).isEqualTo(1);
    }

    @Test
    void weakLikelyParkingDegradesToUncertain() {
        provider.analysis = new VisionProviderClient.VisionAnalysis("LIKELY_PARKING", 0.55, "EMPTY_SPACE_VISIBLE");
        assertThat(classifier.classify(UUID.randomUUID())).isEqualTo(Verdict.UNCERTAIN);
    }

    @Test
    void confidentNotParkingStands() {
        provider.analysis = new VisionProviderClient.VisionAnalysis("NOT_A_PARKING_SPOT", 0.95, "UNRELATED_SUBJECT");
        assertThat(classifier.classify(UUID.randomUUID())).isEqualTo(Verdict.NOT_A_PARKING_SPOT);
    }

    @Test
    void weakNotParkingDegradesToUncertainForHumanReview() {
        provider.analysis = new VisionProviderClient.VisionAnalysis("NOT_A_PARKING_SPOT", 0.5, "UNRELATED_SUBJECT");
        assertThat(classifier.classify(UUID.randomUUID())).isEqualTo(Verdict.UNCERTAIN);
    }

    @Test
    void uncertainStaysUncertainRegardlessOfConfidence() {
        provider.analysis = new VisionProviderClient.VisionAnalysis("UNCERTAIN", 0.99, "TOO_DARK_OR_BLURRY");
        assertThat(classifier.classify(UUID.randomUUID())).isEqualTo(Verdict.UNCERTAIN);
    }

    // --- fail-closed: media retrieval --------------------------------------

    @Test
    void missingMediaFailsClosed() {
        fetcher.failure = new MediaContentException(MediaContentException.Reason.NOT_FOUND, "gone");
        assertThat(classifier.classify(UUID.randomUUID())).isEqualTo(Verdict.UNCERTAIN);
        assertThat(provider.calls).isZero();
        assertThat(failClosedCount()).isEqualTo(1.0);
    }

    @Test
    void oversizedImageFailsClosed() {
        fetcher.failure = new MediaContentException(MediaContentException.Reason.TOO_LARGE, "big");
        assertThat(classifier.classify(UUID.randomUUID())).isEqualTo(Verdict.UNCERTAIN);
    }

    @Test
    void unsupportedContentTypeFailsClosed() {
        fetcher.failure = new MediaContentException(MediaContentException.Reason.UNSUPPORTED_TYPE, "gif");
        assertThat(classifier.classify(UUID.randomUUID())).isEqualTo(Verdict.UNCERTAIN);
    }

    @Test
    void mediaServiceOutageFailsClosed() {
        fetcher.failure = new MediaContentException(MediaContentException.Reason.UNAVAILABLE, "down");
        assertThat(classifier.classify(UUID.randomUUID())).isEqualTo(Verdict.UNCERTAIN);
    }

    // --- fail-closed: provider ----------------------------------------------

    @Test
    void providerFailuresNeverProduceLikelyParking() {
        for (VisionProviderException.Category category : VisionProviderException.Category.values()) {
            provider.failure = new VisionProviderException(category, category.name());
            assertThat(classifier.classify(UUID.randomUUID()))
                    .as("category %s", category)
                    .isEqualTo(Verdict.UNCERTAIN);
        }
    }

    @Test
    void unexpectedRuntimeFailureFailsClosed() {
        provider.runtimeFailure = new IllegalStateException("boom");
        assertThat(classifier.classify(UUID.randomUUID())).isEqualTo(Verdict.UNCERTAIN);
    }

    // --- provider-call dedup -------------------------------------------------

    @Test
    void passedHistoryReusesLikelyParkingWithoutProviderCall() {
        UUID mediaId = UUID.randomUUID();
        results.add(resultFor(mediaId, Verdict.LIKELY_PARKING));

        assertThat(classifier.classify(mediaId)).isEqualTo(Verdict.LIKELY_PARKING);
        assertThat(provider.calls).isZero();
        assertThat(fetcher.calls).isZero();
    }

    @Test
    void failedHistoryReusesNotParkingWithoutProviderCall() {
        UUID mediaId = UUID.randomUUID();
        results.add(resultFor(mediaId, Verdict.NOT_A_PARKING_SPOT));

        assertThat(classifier.classify(mediaId)).isEqualTo(Verdict.NOT_A_PARKING_SPOT);
        assertThat(provider.calls).isZero();
    }

    @Test
    void infrastructureWarningIsNotReusedAsSemanticCache() {
        UUID mediaId = UUID.randomUUID();
        results.add(infraWarning(mediaId));
        provider.analysis = new VisionProviderClient.VisionAnalysis("LIKELY_PARKING", 0.9, "EMPTY_SPACE_VISIBLE");

        assertThat(classifier.classify(mediaId)).isEqualTo(Verdict.LIKELY_PARKING);
        assertThat(provider.calls).isEqualTo(1);
    }

    @Test
    void semanticUncertainWithinTtlIsReused() {
        UUID mediaId = UUID.randomUUID();
        results.add(resultFor(mediaId, Verdict.UNCERTAIN));
        provider.analysis = new VisionProviderClient.VisionAnalysis("LIKELY_PARKING", 0.9, "EMPTY_SPACE_VISIBLE");

        assertThat(classifier.classify(mediaId)).isEqualTo(Verdict.UNCERTAIN);
        assertThat(provider.calls).isZero();
    }

    @Test
    void otherMediaHistoryDoesNotAffectClassification() {
        results.add(resultFor(UUID.randomUUID(), Verdict.LIKELY_PARKING));
        provider.analysis = new VisionProviderClient.VisionAnalysis("UNCERTAIN", 0.4, "OTHER");

        assertThat(classifier.classify(UUID.randomUUID())).isEqualTo(Verdict.UNCERTAIN);
        assertThat(provider.calls).isEqualTo(1);
    }

    @Test
    void simultaneousClassifySharesOneProviderCall() throws Exception {
        UUID mediaId = UUID.randomUUID();
        provider.analysis = new VisionProviderClient.VisionAnalysis("LIKELY_PARKING", 0.95, "EMPTY_SPACE_VISIBLE");
        provider.delayMs = 150;

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var futures = java.util.List.of(
                    pool.submit(() -> classifier.classify(mediaId)),
                    pool.submit(() -> classifier.classify(mediaId)),
                    pool.submit(() -> classifier.classify(mediaId)),
                    pool.submit(() -> classifier.classify(mediaId)));
            for (var f : futures) {
                assertThat(f.get()).isEqualTo(Verdict.LIKELY_PARKING);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(provider.calls).isEqualTo(1);
        assertThat(fetcher.calls).isEqualTo(1);
    }

    @Test
    void failureReleasesSingleFlightGuardForRetry() {
        UUID mediaId = UUID.randomUUID();
        provider.runtimeFailure = new IllegalStateException("boom");
        assertThat(classifier.classify(mediaId)).isEqualTo(Verdict.UNCERTAIN);

        provider.runtimeFailure = null;
        provider.analysis = new VisionProviderClient.VisionAnalysis("LIKELY_PARKING", 0.9, "EMPTY_SPACE_VISIBLE");
        assertThat(classifier.classify(mediaId)).isEqualTo(Verdict.LIKELY_PARKING);
        assertThat(provider.calls).isEqualTo(2);
    }

    // --- helpers --------------------------------------------------------------

    private double failClosedCount() {
        return registry.counter("parkio.ai.vision.validations",
                "provider", "gemini", "outcome", "FAIL_CLOSED").count();
    }

    /** Builds a persisted-result fixture whose status matches the given verdict. */
    private static AiValidationResult resultFor(UUID mediaId, Verdict verdict) {
        return new DeterministicAiValidator(id -> verdict)
                .validate(mediaId, null, null, NOW);
    }

    private static AiValidationResult infraWarning(UUID mediaId) {
        ContentRiskClassifier infrastructure = new ContentRiskClassifier() {
            @Override
            public Verdict classify(UUID id) {
                return Verdict.UNCERTAIN;
            }

            @Override
            public com.parkio.aivalidation.domain.ContentClassification classifyDetailed(UUID id) {
                return com.parkio.aivalidation.domain.ContentClassification.infrastructure("timeout");
            }
        };
        return new DeterministicAiValidator(infrastructure).validate(mediaId, null, null, NOW);
    }

    private static final class FakeFetcher implements MediaContentFetcher {
        private MediaContentException failure;
        private int calls;

        @Override
        public MediaContent fetch(UUID mediaId) {
            calls++;
            if (failure != null) {
                throw failure;
            }
            return new MediaContent(JPEG, "image/jpeg");
        }
    }

    private static final class FakeProvider implements VisionProviderClient {
        private VisionAnalysis analysis =
                new VisionAnalysis("UNCERTAIN", 0.5, "OTHER");
        private VisionProviderException failure;
        private RuntimeException runtimeFailure;
        private int calls;
        private long delayMs;

        @Override
        public String providerId() {
            return "gemini";
        }

        @Override
        public String modelId() {
            return "test-model";
        }

        @Override
        public VisionAnalysis analyze(byte[] imageBytes, String contentType) {
            calls++;
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (failure != null) {
                throw failure;
            }
            if (runtimeFailure != null) {
                throw runtimeFailure;
            }
            return analysis;
        }
    }

    private static final class FakeResults implements AiValidationResultRepository {
        private final List<AiValidationResult> stored = new ArrayList<>();

        void add(AiValidationResult result) {
            stored.add(result);
        }

        @Override
        public AiValidationResult save(AiValidationResult result) {
            stored.add(result);
            return result;
        }

        @Override
        public Optional<AiValidationResult> findById(UUID id) {
            return stored.stream().filter(r -> r.id().equals(id)).findFirst();
        }

        @Override
        public List<AiValidationResult> findByMediaId(UUID mediaId) {
            return stored.stream().filter(r -> r.mediaId().equals(mediaId)).toList();
        }

        @Override
        public List<AiValidationResult> findByParkingSpotId(UUID parkingSpotId) {
            return stored.stream()
                    .filter(r -> r.parkingSpotId().map(parkingSpotId::equals).orElse(false))
                    .toList();
        }

        @Override
        public List<AiValidationResult> findByStatusAndCreatedAtBetween(
                com.parkio.aivalidation.domain.AiValidationStatus status,
                Instant oldestInclusive, Instant newestExclusive, int limit) {
            return stored.stream()
                    .filter(r -> r.status() == status)
                    .filter(r -> !r.createdAt().isBefore(oldestInclusive)
                            && r.createdAt().isBefore(newestExclusive))
                    .limit(limit)
                    .toList();
        }
    }
}
