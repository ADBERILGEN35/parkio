package com.parkio.auth.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.auth.domain.event.UserErasureRequestedEvent;
import com.parkio.auth.domain.event.UserErasureRestoreReplayRequestedEvent;
import com.parkio.auth.infrastructure.config.KafkaTopicsConfig;
import org.junit.jupiter.api.Test;

/** Restore replay commands travel on the same erasure topic as live erasure requests. */
class AuthOutboxRelayRestoreReplayRoutingTest {

    @Test
    void restoreReplayCommandsGoToThePrivacyErasureTopic() {
        assertThat(AuthOutboxRelay.topicFor(UserErasureRestoreReplayRequestedEvent.TYPE))
                .isEqualTo(KafkaTopicsConfig.PRIVACY_ERASURE)
                .isEqualTo(AuthOutboxRelay.topicFor(UserErasureRequestedEvent.TYPE));
    }
}
