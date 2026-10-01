package com.parkio.moderation.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** U05: erasure ACK rows route to the auth-owned erasure topic; existing routing is unchanged. */
class ModerationOutboxRelayErasureRoutingTest {

    @Test
    void erasureAckRoutesToPrivacyErasureTopic() {
        assertThat(ModerationOutboxRelay.topicFor("UserErasureAcknowledged")).isEqualTo("parkio.privacy.erasure");
    }

    @Test
    void existingTypesKeepTheirTopics() {
        assertThat(ModerationOutboxRelay.topicFor("ModerationCaseOpened")).isEqualTo("parkio.moderation.case");
        assertThat(ModerationOutboxRelay.topicFor("AppealCreated")).isEqualTo("parkio.moderation.case");
        assertThat(ModerationOutboxRelay.topicFor("UserSuspended")).isEqualTo("parkio.moderation.action");
        assertThat(ModerationOutboxRelay.topicFor("ParkingSpotApprovedByModerator")).isEqualTo("parkio.moderation.action");
    }

    @Test
    void unknownTypesStayUnroutable() {
        assertThat(ModerationOutboxRelay.topicFor("SomethingElse")).isNull();
    }
}
