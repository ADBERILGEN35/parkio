package com.parkio.analytics.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** U05: erasure ACK rows route to the auth-owned erasure topic; existing routing is unchanged. */
class AnalyticsOutboxRelayErasureRoutingTest {

    @Test
    void erasureAckRoutesToPrivacyErasureTopic() {
        assertThat(AnalyticsOutboxRelay.topicFor("AccountErasure")).isEqualTo("parkio.privacy.erasure");
    }

    @Test
    void analyticsPublishesNothingElse() {
        assertThat(AnalyticsOutboxRelay.topicFor("ParkingSession")).isNull();
        assertThat(AnalyticsOutboxRelay.topicFor("AnalyticsEvent")).isNull();
    }

    @Test
    void unknownTypesStayUnroutable() {
        assertThat(AnalyticsOutboxRelay.topicFor("SomethingElse")).isNull();
    }
}
