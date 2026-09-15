package com.parkio.parking.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.application.AccountErasureHandler;
import com.parkio.parking.application.event.UserErasureRequestedEvent;
import com.parkio.platform.messaging.EventEnvelope;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "parkio.kafka.erasure-consumer.enabled", havingValue = "true", matchIfMissing = true)
public class UserErasureKafkaConsumer {

    public static final String TOPIC = "parkio.privacy.erasure";
    public static final String GROUP = "parkio.parking.erasure";

    private static final Logger log = LoggerFactory.getLogger(UserErasureKafkaConsumer.class);

    private final AccountErasureHandler handler;
    private final ObjectMapper objectMapper;

    public UserErasureKafkaConsumer(AccountErasureHandler handler, ObjectMapper objectMapper) {
        this.handler = handler;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = TOPIC,
            groupId = GROUP,
            containerFactory = "parkingKafkaListenerContainerFactory")
    public void onMessage(ConsumerRecord<String, String> record,
                          @Header(name = "eventType", required = false) String eventTypeHeader,
                          Acknowledgment ack) throws Exception {
        EventEnvelope envelope = objectMapper.readValue(record.value(), EventEnvelope.class);
        String eventType = eventTypeHeader != null ? eventTypeHeader : envelope.eventType();
        if (!UserErasureRequestedEvent.TYPE.equals(eventType)) {
            log.debug("Ignoring event type {} on {}", eventType, TOPIC);
            ack.acknowledge();
            return;
        }
        handler.handle(objectMapper.treeToValue(envelope.payload(), UserErasureRequestedEvent.class));
        ack.acknowledge();
    }
}
