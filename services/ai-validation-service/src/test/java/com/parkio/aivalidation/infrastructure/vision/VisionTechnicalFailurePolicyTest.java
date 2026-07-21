package com.parkio.aivalidation.infrastructure.vision;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.aivalidation.application.port.AiValidationResultRepository;
import com.parkio.aivalidation.domain.AiValidationResult;
import com.parkio.aivalidation.domain.AiValidationStatus;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Technical / infrastructure failures must never produce FAILED / REJECT.
 * They fail closed to UNCERTAIN → WARNING (REVIEW / PENDING_REVIEW).
 */
class VisionTechnicalFailurePolicyTest {

    private static final Instant NOW = Instant.parse("2026-07-20T12:00:00Z");
    private static final byte[] JPEG = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3};

    private FakeFetcher fetcher;
    private FakeProvider provider;
    private VisionContentRiskClassifier classifier;
    private DeterministicAiValidator validator;

    @BeforeEach
    void setUp() {
        fetcher = new FakeFetcher();
        provider = new FakeProvider();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        classifier = new VisionContentRiskClassifier(
                fetcher, provider, new EmptyResults(), new VisionProperties(),
                new VisionMetrics(registry, "gemini"), Clock.fixed(NOW, ZoneOffset.UTC));
        validator = new DeterministicAiValidator(classifier);
    }

    @ParameterizedTest
    @EnumSource(VisionProviderException.Category.class)
    void providerCategoriesNeverProduceFailedStatus(VisionProviderException.Category category) {
        provider.failure = new VisionProviderException(category, category.name());
        AiValidationResult result = validator.validate(UUID.randomUUID(), null, null, NOW);
        assertThat(result.status()).isEqualTo(AiValidationStatus.WARNING);
        assertThat(classifier.classify(UUID.randomUUID())).isEqualTo(Verdict.UNCERTAIN);
    }

    @Test
    void geminiTimeoutNeverRejects() {
        provider.failure = new VisionProviderException(
                VisionProviderException.Category.TIMEOUT, "timeout");
        assertNeverFailed();
    }

    @Test
    void networkUnavailableNeverRejects() {
        provider.failure = new VisionProviderException(
                VisionProviderException.Category.UNAVAILABLE, "network");
        assertNeverFailed();
    }

    @Test
    void malformedGeminiJsonNeverRejects() {
        provider.failure = new VisionProviderException(
                VisionProviderException.Category.MALFORMED_RESPONSE, "bad json");
        assertNeverFailed();
    }

    @Test
    void emptyModelResponseNeverRejects() {
        provider.failure = new VisionProviderException(
                VisionProviderException.Category.MALFORMED_RESPONSE, "no text part");
        assertNeverFailed();
    }

    @Test
    void unsupportedReasonCodeForcedToUncertainNeverRejects() {
        provider.analysis = new VisionProviderClient.VisionAnalysis(
                "NOT_A_PARKING_SPOT", 0.99, "SOME_UNKNOWN_CODE_FROM_MODEL");
        AiValidationResult result = validator.validate(UUID.randomUUID(), null, null, NOW);
        assertThat(result.status()).isEqualTo(AiValidationStatus.WARNING);
        assertThat(classifier.classify(UUID.randomUUID())).isEqualTo(Verdict.UNCERTAIN);
    }

    @Test
    void mediaFetchFailureNeverRejects() {
        fetcher.failure = new MediaContentException(
                MediaContentException.Reason.UNAVAILABLE, "fetch failed");
        assertNeverFailed();
    }

    @Test
    void mediaNotFoundNeverRejects() {
        fetcher.failure = new MediaContentException(
                MediaContentException.Reason.NOT_FOUND, "gone");
        assertNeverFailed();
    }

    @Test
    void temporaryInternalApiFailureNeverRejects() {
        provider.runtimeFailure = new IllegalStateException("temporary internal");
        assertNeverFailed();
    }

    @Test
    void missingClaimedRegionWholeImageBiasesUncertainNotAutoReject() {
        // Provider returns soft whole-image code; classifier must not hard-reject.
        provider.analysis = new VisionProviderClient.VisionAnalysis(
                "NOT_A_PARKING_SPOT", 0.99, "WHOLE_IMAGE_NO_REGION",
                "UNCERTAIN", "UNCERTAIN", "NEARBY_ONLY", "UNCERTAIN", null, null);
        assertThat(classifier.classify(UUID.randomUUID())).isEqualTo(Verdict.UNCERTAIN);
        AiValidationResult result = validator.validate(UUID.randomUUID(), null, null, NOW);
        assertThat(result.status()).isEqualTo(AiValidationStatus.WARNING);
        assertThat(result.findings()).anyMatch(f ->
                f.message() != null && f.message().contains("WHOLE_IMAGE_NO_REGION"));
    }

    private void assertNeverFailed() {
        UUID mediaId = UUID.randomUUID();
        assertThat(classifier.classify(mediaId)).isEqualTo(Verdict.UNCERTAIN);
        AiValidationResult result = validator.validate(mediaId, null, null, NOW);
        assertThat(result.status()).isNotEqualTo(AiValidationStatus.FAILED);
        assertThat(result.status()).isEqualTo(AiValidationStatus.WARNING);
    }

    private static final class EmptyResults implements AiValidationResultRepository {
        @Override
        public AiValidationResult save(AiValidationResult result) {
            return result;
        }

        @Override
        public Optional<AiValidationResult> findById(UUID id) {
            return Optional.empty();
        }

        @Override
        public List<AiValidationResult> findByMediaId(UUID mediaId) {
            return List.of();
        }

        @Override
        public List<AiValidationResult> findByParkingSpotId(UUID parkingSpotId) {
            return List.of();
        }

        @Override
        public List<AiValidationResult> findByStatusAndCreatedAtBetween(
                AiValidationStatus status, Instant oldestInclusive, Instant newestExclusive, int limit) {
            return List.of();
        }
    }

    private static final class FakeFetcher implements MediaContentFetcher {
        MediaContentException failure;
        @Override
        public MediaContent fetch(UUID mediaId) {
            if (failure != null) {
                throw failure;
            }
            return new MediaContent(JPEG, "image/jpeg", null);
        }
    }

    private static final class FakeProvider implements VisionProviderClient {
        VisionAnalysis analysis = new VisionAnalysis("UNCERTAIN", 0.5, "OTHER");
        VisionProviderException failure;
        RuntimeException runtimeFailure;
        @Override
        public String providerId() {
            return "gemini";
        }
        @Override
        public String modelId() {
            return "test";
        }
        @Override
        public VisionAnalysis analyze(byte[] imageBytes, String contentType, ClaimedRegion claimedRegion) {
            if (runtimeFailure != null) {
                throw runtimeFailure;
            }
            if (failure != null) {
                throw failure;
            }
            return analysis;
        }
    }
}