package com.parkio.notification.infrastructure.delivery;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.parkio.notification.application.port.PushMessage;
import com.parkio.notification.application.port.PushNotificationSender;
import com.parkio.notification.application.port.PushSendResult;
import com.parkio.notification.infrastructure.metrics.PushSenderMetrics;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Expo Push API sender for mobile device tokens ({@code ExponentPushToken[...]}).
 * Activated when {@code parkio.notification.delivery.push.provider=expo}.
 */
@Component
@ConditionalOnProperty(name = "parkio.notification.delivery.push.provider", havingValue = "expo")
public class ExpoPushNotificationSender implements PushNotificationSender {

    static final String FAILURE_INVALID_DEVICE_TOKEN = "INVALID_DEVICE_TOKEN";
    static final String FAILURE_PROVIDER_UNAVAILABLE = "PROVIDER_UNAVAILABLE";
    static final String FAILURE_PROVIDER_REJECTED = "PROVIDER_REJECTED";

    private static final Logger log = LoggerFactory.getLogger(ExpoPushNotificationSender.class);

    private final RestClient expo;
    private final PushSenderMetrics metrics;
    private final String defaultChannelId;

    public ExpoPushNotificationSender(
            RestClient expoPushRestClient,
            PushSenderMetrics metrics,
            @Value("${parkio.notification.delivery.push.expo.android-channel-id:default}") String defaultChannelId) {
        this.expo = expoPushRestClient;
        this.metrics = metrics;
        this.defaultChannelId = defaultChannelId;
    }

    @Override
    public PushSendResult send(PushMessage message) {
        try {
            ExpoPushResponse response = expo.post()
                    .uri("/push/send")
                    .body(ExpoPushRequest.of(message, defaultChannelId))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, httpResponse) -> {
                        throw new ExpoPushException("Expo push API rejected request with status "
                                + httpResponse.getStatusCode());
                    })
                    .body(ExpoPushResponse.class);
            if (response == null || response.data() == null || response.data().isEmpty()) {
                metrics.providerUnavailable();
                return PushSendResult.failed(FAILURE_PROVIDER_UNAVAILABLE);
            }
            ExpoTicket ticket = response.data().getFirst();
            if ("ok".equalsIgnoreCase(ticket.status())) {
                metrics.sent();
                return PushSendResult.sent(ticket.id() == null ? "expo-ok" : ticket.id());
            }
            String error = ticket.message() == null ? "UNKNOWN" : ticket.message();
            if (isInvalidToken(error)) {
                metrics.invalidToken();
                return PushSendResult.failed(FAILURE_INVALID_DEVICE_TOKEN);
            }
            metrics.failed();
            log.warn("Expo push ticket error: {}", error);
            return PushSendResult.failed(FAILURE_PROVIDER_REJECTED);
        } catch (ExpoPushException ex) {
            metrics.providerUnavailable();
            log.warn("Expo push provider unavailable: {}", ex.getMessage());
            return PushSendResult.failed(FAILURE_PROVIDER_UNAVAILABLE);
        } catch (RestClientException ex) {
            metrics.providerUnavailable();
            log.warn("Expo push network failure: {}", ex.getClass().getSimpleName());
            return PushSendResult.failed(FAILURE_PROVIDER_UNAVAILABLE);
        }
    }

    private static boolean isInvalidToken(String message) {
        return "DeviceNotRegistered".equalsIgnoreCase(message)
                || "InvalidCredentials".equalsIgnoreCase(message);
    }

    private record ExpoPushRequest(
            String to,
            String title,
            String body,
            Map<String, String> data,
            String channelId,
            String sound,
            String priority) {

        static ExpoPushRequest of(PushMessage message, String channelId) {
            return new ExpoPushRequest(
                    message.deviceToken(),
                    message.title(),
                    message.body(),
                    message.data().isEmpty() ? null : message.data(),
                    channelId,
                    null,
                    "default");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ExpoPushResponse(List<ExpoTicket> data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ExpoTicket(
            String status,
            String id,
            String message,
            @JsonProperty("details")
            Map<String, Object> details) {
    }

    private static final class ExpoPushException extends RuntimeException {
        private ExpoPushException(String message) {
            super(message);
        }
    }
}