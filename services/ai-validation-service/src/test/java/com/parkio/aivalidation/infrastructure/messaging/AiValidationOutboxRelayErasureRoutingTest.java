package com.parkio.aivalidation.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** U05: erasure ACK rows route to the auth-owned erasure topic; existing routing is unchanged. */
class AiValidationOutboxRelayErasureRoutingTest {

    @Test
    void erasureAckRoutesToPrivacyErasureTopic() {
        assertThat(AiValidationOutboxRelay.topicFor("AccountErasure")).isEqualTo("parkio.privacy.erasure");
    }

    @Test
    void existingTypesKeepTheirTopics() {
        assertThat(AiValidationOutboxRelay.topicFor("AiValidationResult")).isEqualTo("parkio.aivalidation.result");
    }

    @Test
    void unknownTypesStayUnroutable() {
        assertThat(AiValidationOutboxRelay.topicFor("SomethingElse")).isNull();
    }
}
