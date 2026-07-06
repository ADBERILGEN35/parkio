package com.parkio.notification.infrastructure.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.parkio.notification.application.port.PushMessage;
import com.parkio.notification.application.port.PushSendResult;
import com.parkio.notification.domain.DevicePlatform;
import com.parkio.notification.infrastructure.metrics.PushSenderMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ExpoPushNotificationSenderTest {

    private static final String SECRET_TOKEN = "ExponentPushToken[secret-device-token]";

    private SimpleMeterRegistry registry;
    private MockRestServiceServer server;
    private ExpoPushNotificationSender sender;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        RestClient.Builder builder = RestClient.builder().baseUrl("https://exp.host/--/api/v2");
        server = MockRestServiceServer.bindTo(builder).build();
        sender = new ExpoPushNotificationSender(builder.build(), new PushSenderMetrics(registry), "default");
    }

    @Test
    void sendsPushWithRouteDataWithoutLoggingToken() {
        server.expect(requestTo("https://exp.host/--/api/v2/push/send"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.to").value(SECRET_TOKEN))
                .andExpect(jsonPath("$.data.route").value("/(main)/map"))
                .andRespond(withSuccess(
                        "{\"data\":[{\"status\":\"ok\",\"id\":\"ticket-1\"}]}",
                        MediaType.APPLICATION_JSON));

        PushSendResult result = sender.send(new PushMessage(
                SECRET_TOKEN,
                DevicePlatform.ANDROID,
                "Title",
                "Body",
                Map.of("route", "/(main)/map")));

        server.verify();
        assertThat(result.delivered()).isTrue();
        assertThat(registry.counter("parkio.notification.push.sent.count").count()).isEqualTo(1.0);
    }

    @Test
    void invalidTokenReturnsFailureReasonForCleanup() {
        // Real Expo ticket shape: the machine-readable code lives in details.error,
        // while message is prose that embeds the device token.
        server.expect(requestTo("https://exp.host/--/api/v2/push/send"))
                .andRespond(withSuccess(
                        "{\"data\":[{\"status\":\"error\",\"message\":\"\\\"" + SECRET_TOKEN
                                + "\\\" is not a registered push notification recipient\","
                                + "\"details\":{\"error\":\"DeviceNotRegistered\"}}]}",
                        MediaType.APPLICATION_JSON));

        PushSendResult result = sender.send(new PushMessage(
                SECRET_TOKEN, DevicePlatform.IOS, "Title", "Body"));

        assertThat(result.delivered()).isFalse();
        assertThat(result.failureReason()).isEqualTo(ExpoPushNotificationSender.FAILURE_INVALID_DEVICE_TOKEN);
        assertThat(registry.counter("parkio.notification.push.invalid_token.count").count()).isEqualTo(1.0);
    }

    @Test
    void invalidTokenCodeInMessageOnlyIsStillRecognised() {
        server.expect(requestTo("https://exp.host/--/api/v2/push/send"))
                .andRespond(withSuccess(
                        "{\"data\":[{\"status\":\"error\",\"message\":\"DeviceNotRegistered\"}]}",
                        MediaType.APPLICATION_JSON));

        PushSendResult result = sender.send(new PushMessage(
                SECRET_TOKEN, DevicePlatform.IOS, "Title", "Body"));

        assertThat(result.delivered()).isFalse();
        assertThat(result.failureReason()).isEqualTo(ExpoPushNotificationSender.FAILURE_INVALID_DEVICE_TOKEN);
    }

    @Test
    void otherTicketErrorsAreProviderRejected() {
        server.expect(requestTo("https://exp.host/--/api/v2/push/send"))
                .andRespond(withSuccess(
                        "{\"data\":[{\"status\":\"error\",\"message\":\"rate limited\","
                                + "\"details\":{\"error\":\"MessageRateExceeded\"}}]}",
                        MediaType.APPLICATION_JSON));

        PushSendResult result = sender.send(new PushMessage(
                SECRET_TOKEN, DevicePlatform.ANDROID, "Title", "Body"));

        assertThat(result.delivered()).isFalse();
        assertThat(result.failureReason()).isEqualTo(ExpoPushNotificationSender.FAILURE_PROVIDER_REJECTED);
        assertThat(registry.counter("parkio.notification.push.failed.count").count()).isEqualTo(1.0);
    }
}