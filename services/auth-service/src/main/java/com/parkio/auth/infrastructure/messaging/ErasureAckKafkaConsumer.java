package com.parkio.auth.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.auth.application.AccountErasureApplicationService;
import com.parkio.auth.domain.event.UserErasureAcknowledgedEvent;
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

    private static final Logger log = LoggerFactory.getLogger(ErasureAckKafkaConsumer.class);

    private final AccountErasureApplicationService erasure;
    private final ObjectMapper objectMapper;

    public ErasureAckKafkaConsumer(AccountErasureApplicationService erasure, ObjectMapper objectMapper) {
        this.erasure = erasure;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = TOPIC,
            groupId = GROUP,
            containerFactory = "authKafkaListenerContainerFactory")
    public void onMessage(ConsumerRecord<String, String> record,
                          @Header(name = "eventType", required = false) String eventTypeHeader,
                          Acknowledgment ack) throws Exception {
        EventEnvelope envelope = objectMapper.readValue(record.value(), EventEnvelope.class);
        String eventType = eventTypeHeader != null ? eventTypeHeader : envelope.eventType();
        if (UserErasureAcknowledgedEvent.TYPE.equals(eventType)) {
            erasure.handleAcknowledgement(
                    objectMapper.treeToValue(envelope.payload(), UserErasureAcknowledgedEvent.class));
        } else {
            log.debug("Ignoring event type {} on {}", eventType, TOPIC);
        }
        ack.acknowledge();
    }
}
