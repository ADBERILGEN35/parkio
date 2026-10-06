package com.parkio.auth.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.auth.application.AccountErasureApplicationService;
import com.parkio.auth.application.ErasureRestoreReplayService;
import com.parkio.auth.domain.event.UserErasureAcknowledgedEvent;
import com.parkio.auth.domain.event.UserErasureRestoreAcknowledgedEvent;
import com.parkio.auth.infrastructure.recovery.RecoveryReplayLaunch;
import com.parkio.platform.messaging.EventEnvelope;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
public class ErasureAckKafkaConsumer {

    public static final String TOPIC = "parkio.privacy.erasure";
    public static final String GROUP = "parkio.auth.erasure";
    /**
     * The group of the recovery-replay command (PR #295 review B5): even pointed at a Kafka that is
     * not isolated, the one-shot never joins the live auth group and never takes its partitions.
     */
    public static final String RECOVERY_GROUP = "parkio.auth.erasure.recovery-replay";
    static final String GROUP_FOR_PROFILE = "#{environment.matchesProfiles('" + RecoveryReplayLaunch.PROFILE
            + "') ? '" + RECOVERY_GROUP + "' : '" + GROUP + "'}";

    private static final Logger log = LoggerFactory.getLogger(ErasureAckKafkaConsumer.class);

    private final AccountErasureApplicationService erasure;
    private final ErasureRestoreReplayService restoreReplay;
    private final ObjectMapper objectMapper;

    public ErasureAckKafkaConsumer(AccountErasureApplicationService erasure, ErasureRestoreReplayService restoreReplay,
                                   ObjectMapper objectMapper) {
        this.erasure = erasure;
        this.restoreReplay = restoreReplay;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = TOPIC,
            groupId = GROUP_FOR_PROFILE,
            containerFactory = "authKafkaListenerContainerFactory")
    public void onMessage(ConsumerRecord<String, String> record,
                          @Header(name = "eventType", required = false) String eventTypeHeader,
                          Acknowledgment ack) throws Exception {
        EventEnvelope envelope = objectMapper.readValue(record.value(), EventEnvelope.class);
        String eventType = eventTypeHeader != null ? eventTypeHeader : envelope.eventType();
        if (UserErasureAcknowledgedEvent.TYPE.equals(eventType)) {
            erasure.handleAcknowledgement(
                    objectMapper.treeToValue(envelope.payload(), UserErasureAcknowledgedEvent.class));
        } else if (UserErasureRestoreAcknowledgedEvent.TYPE.equals(eventType)) {
            restoreReplay.handleAcknowledgement(
                    objectMapper.treeToValue(envelope.payload(), UserErasureRestoreAcknowledgedEvent.class));
        } else {
            log.debug("Ignoring event type {} on {}", eventType, TOPIC);
        }
        ack.acknowledge();
    }
}
