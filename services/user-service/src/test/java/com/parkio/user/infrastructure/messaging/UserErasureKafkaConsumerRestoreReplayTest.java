package com.parkio.user.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.parkio.platform.messaging.EventEnvelope;
import com.parkio.user.application.AccountErasureHandler;
import com.parkio.user.application.event.UserErasureRestoreReplayRequestedEvent;
import com.parkio.user.domain.event.UserErasureRestoreAcknowledgedEvent;
import com.parkio.user.infrastructure.config.KafkaTopicsConfig;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Restore replay commands share the erasure topic. They are handled only with
 * {@code parkio.privacy.restore-replay.enabled=true}; otherwise they are acknowledged and skipped.
 * Their ACKs go back on the erasure topic.
 */
class UserErasureKafkaConsumerRestoreReplayTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final AccountErasureHandler handler = mock(AccountErasureHandler.class);

    @Test
    void anEnabledConsumerReplaysTheErasureForTheAttempt() throws Exception {
        UUID attempt = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        AtomicBoolean acked = new AtomicBoolean();

        new UserErasureKafkaConsumer(handler, objectMapper, true)
                .onMessage(record(attempt, user), UserErasureRestoreReplayRequestedEvent.TYPE, () -> acked.set(true));

        ArgumentCaptor<UserErasureRestoreReplayRequestedEvent> replayed =
                ArgumentCaptor.forClass(UserErasureRestoreReplayRequestedEvent.class);
        verify(handler).replayForRestore(replayed.capture());
        assertThat(replayed.getValue().recoveryAttemptId()).isEqualTo(attempt);
        assertThat(replayed.getValue().authUserId()).isEqualTo(user);
        assertThat(replayed.getValue().restoredDatasetId()).isEqualTo("backup-stamp");
        assertThat(acked).isTrue();
    }

    @Test
    void aDisabledConsumerSkipsTheCommandButAcknowledgesTheRecord() throws Exception {
        AtomicBoolean acked = new AtomicBoolean();

        new UserErasureKafkaConsumer(handler, objectMapper, false)
                .onMessage(record(UUID.randomUUID(), UUID.randomUUID()),
                        UserErasureRestoreReplayRequestedEvent.TYPE, () -> acked.set(true));

        verifyNoInteractions(handler);
        assertThat(acked).isTrue();
    }

    @Test
    void restoreAcksAreRoutedToTheErasureTopic() {
        assertThat(UserOutboxRelay.topicFor(UserErasureRestoreAcknowledgedEvent.AGGREGATE_TYPE)).isEqualTo(KafkaTopicsConfig.PRIVACY_ERASURE);
    }

    private ConsumerRecord<String, String> record(UUID attempt, UUID user) throws Exception {
        UUID eventId = UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", eventId.toString());
        payload.put("recoveryAttemptId", attempt.toString());
        payload.put("restoredDatasetId", "backup-stamp");
        payload.put("erasureSetDigest", "b".repeat(64));
        payload.put("authUserId", user.toString());
        payload.put("erasedAt", "2026-09-29T08:16:00Z");
        payload.put("occurredAt", "2026-10-03T00:00:00Z");
        EventEnvelope envelope = new EventEnvelope(eventId, UserErasureRestoreReplayRequestedEvent.TYPE,
                "AccountErasure", user, Instant.parse("2026-10-03T00:00:00Z"), 1, null, objectMapper.valueToTree(payload));
        return new ConsumerRecord<>(UserErasureKafkaConsumer.TOPIC, 0, 0L, user.toString(),
                objectMapper.writeValueAsString(envelope));
    }
}
