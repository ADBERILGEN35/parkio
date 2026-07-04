package com.parkio.analytics.infrastructure.messaging;

import com.parkio.platform.messaging.EventEnvelope;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.analytics.application.AnalyticsApplicationService;
import com.parkio.analytics.application.event.NotificationCreatedEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
public class NotificationEventsKafkaConsumer {

    public static final String NOTIFICATION_TOPIC = "parkio.notification.notification";
    public static final String GROUP = "parkio.analytics";

    private static final String NOTIFICATION_CREATED = "NotificationCreated";

    private static final Logger log = LoggerFactory.getLogger(NotificationEventsKafkaConsumer.class);

    private final AnalyticsApplicationService analyticsService;
    private final ObjectMapper objectMapper;

    public NotificationEventsKafkaConsumer(AnalyticsApplicationService analyticsService,
                                           ObjectMapper objectMapper) {
        this.analyticsService = analyticsService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = NOTIFICATION_TOPIC,
            groupId = GROUP,
            containerFactory = "gamificationScoreKafkaListenerContainerFactory")
    public void onMessage(ConsumerRecord<String, String> record,
                          @Header(name = "eventType", required = false) String eventTypeHeader,
                          Acknowledgment ack) throws Exception {
        EventEnvelope envelope = objectMapper.readValue(record.value(), EventEnvelope.class);
        String eventType = eventTypeHeader != null ? eventTypeHeader : envelope.eventType();

        switch (eventType == null ? "" : eventType) {
            case NOTIFICATION_CREATED -> analyticsService.handleNotificationCreated(
                    payload(envelope, NotificationCreatedEvent.class));
            default -> log.debug("Ignoring unsupported event type {} on {}", eventType, NOTIFICATION_TOPIC);
        }
        ack.acknowledge();
    }

    private <T> T payload(EventEnvelope envelope, Class<T> type) throws Exception {
        return objectMapper.treeToValue(envelope.payload(), type);
    }
}