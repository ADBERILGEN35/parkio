package com.parkio.parking.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** U05: erasure ACK rows route to the auth-owned erasure topic; existing routing is unchanged. */
class ParkingOutboxRelayErasureRoutingTest {

    @Test
    void erasureAckRoutesToPrivacyErasureTopic() {
        assertThat(ParkingOutboxRelay.topicFor("AccountErasure")).isEqualTo("parkio.privacy.erasure");
    }

    @Test
    void existingTypesKeepTheirTopics() {
        assertThat(ParkingOutboxRelay.topicFor("ParkingSpot")).isEqualTo("parkio.parking.spot");
        assertThat(ParkingOutboxRelay.topicFor("ParkingSession")).isEqualTo("parkio.parking.session");
    }

    @Test
    void unknownTypesStayUnroutable() {
        assertThat(ParkingOutboxRelay.topicFor("SomethingElse")).isNull();
    }
}
