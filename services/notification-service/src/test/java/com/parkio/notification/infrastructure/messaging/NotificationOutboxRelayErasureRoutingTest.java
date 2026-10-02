package com.parkio.notification.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** U05: erasure ACK rows route to the auth-owned erasure topic; existing routing is unchanged. */
class NotificationOutboxRelayErasureRoutingTest {

    @Test
    void erasureAckRoutesToPrivacyErasureTopic() {
        assertThat(NotificationOutboxRelay.topicFor("AccountErasure")).isEqualTo("parkio.privacy.erasure");
    }

    @Test
    void existingTypesKeepTheirTopics() {
        assertThat(NotificationOutboxRelay.topicFor("Notification")).isEqualTo("parkio.notification.notification");
    }

    @Test
    void unknownTypesStayUnroutable() {
        assertThat(NotificationOutboxRelay.topicFor("SomethingElse")).isNull();
    }
}
