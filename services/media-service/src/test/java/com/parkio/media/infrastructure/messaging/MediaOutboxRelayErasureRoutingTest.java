package com.parkio.media.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** U05: erasure ACK rows route to the auth-owned erasure topic; existing routing is unchanged. */
class MediaOutboxRelayErasureRoutingTest {

    @Test
    void erasureAckRoutesToPrivacyErasureTopic() {
        assertThat(MediaOutboxRelay.topicFor("AccountErasure")).isEqualTo("parkio.privacy.erasure");
    }

    @Test
    void existingTypesKeepTheirTopics() {
        assertThat(MediaOutboxRelay.topicFor("Media")).isEqualTo("parkio.media.media");
    }

    @Test
    void unknownTypesStayUnroutable() {
        assertThat(MediaOutboxRelay.topicFor("SomethingElse")).isNull();
    }
}
