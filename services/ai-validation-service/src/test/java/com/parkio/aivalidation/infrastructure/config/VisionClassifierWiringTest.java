package com.parkio.aivalidation.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.aivalidation.domain.ContentRiskClassifier;
import com.parkio.aivalidation.domain.DeterministicAiValidator;
import com.parkio.aivalidation.infrastructure.classifier.HeuristicContentRiskClassifier;
import com.parkio.aivalidation.infrastructure.vision.VisionContentRiskClassifier;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * Provider switching is configuration-driven: the heuristic classifier stays the
 * default (no provider config), while {@code parkio.ai.vision.provider=gemini} makes
 * the vision classifier primary without touching the domain wiring.
 */
class VisionClassifierWiringTest {

    @Nested
    @SpringBootTest
    class HeuristicByDefault {

        @Autowired
        private ContentRiskClassifier classifier;

        @Autowired
        private ApplicationContext context;

        @Test
        void heuristicClassifierIsTheOnlyImplementation() {
            assertThat(classifier).isInstanceOf(HeuristicContentRiskClassifier.class);
            assertThat(context.getBeanNamesForType(VisionContentRiskClassifier.class)).isEmpty();
        }
    }

    @Nested
    @SpringBootTest(properties = {
            "parkio.ai.vision.provider=gemini",
            "parkio.ai.vision.gemini.api-key=test-only-key",
            "parkio.ai.vision.gemini.base-url=http://localhost:1",
            "parkio.ai.vision.media-client.base-url=http://localhost:1",
            "parkio.ai.vision.revalidation.enabled=false"
    })
    class GeminiEnabled {

        @Autowired
        private ContentRiskClassifier classifier;

        @Autowired
        private DeterministicAiValidator validator;

        @Test
        void visionClassifierBecomesPrimary() {
            assertThat(classifier).isInstanceOf(VisionContentRiskClassifier.class);
            assertThat(validator).isNotNull();
        }
    }
}
