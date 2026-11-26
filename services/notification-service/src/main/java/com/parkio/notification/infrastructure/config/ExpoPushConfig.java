package com.parkio.notification.infrastructure.config;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * Expo Push API client. Both timeouts are bounded (#595): the delivery tick sends while it holds its
 * {@code FOR UPDATE SKIP LOCKED} claims, so an unbounded send would stop push delivery until a restart.
 */
@Configuration
@ConditionalOnProperty(name = "parkio.notification.delivery.push.provider", havingValue = "expo")
public class ExpoPushConfig {

    /** The upper bounds keep a configured value from making a stalled send effectively unbounded again. */
    static final Duration MAX_CONNECT_TIMEOUT = Duration.ofSeconds(30);
    static final Duration MAX_READ_TIMEOUT = Duration.ofSeconds(60);

    private final String accessToken;
    private final Duration connectTimeout;
    private final Duration readTimeout;

    public ExpoPushConfig(
            @Value("${parkio.notification.delivery.push.expo.access-token:}") String accessToken,
            @Value("${parkio.notification.delivery.push.expo.connect-timeout:2s}") Duration connectTimeout,
            @Value("${parkio.notification.delivery.push.expo.read-timeout:5s}") Duration readTimeout) {
        this.accessToken = accessToken;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
    }

    @PostConstruct
    void validate() {
        if (!StringUtils.hasText(accessToken)) {
            throw new IllegalStateException(
                    "PARKIO_EXPO_ACCESS_TOKEN must be configured when parkio.notification.delivery.push.provider=expo");
        }
        requireBounded("parkio.notification.delivery.push.expo.connect-timeout (PARKIO_EXPO_PUSH_CONNECT_TIMEOUT)",
                connectTimeout, MAX_CONNECT_TIMEOUT);
        requireBounded("parkio.notification.delivery.push.expo.read-timeout (PARKIO_EXPO_PUSH_READ_TIMEOUT)",
                readTimeout, MAX_READ_TIMEOUT);
        // The read timeout runs from the start of the exchange, connecting included. A connect timeout that is
        // not shorter would let a connection that never opened end as an unknown outcome instead of a retry.
        if (connectTimeout.compareTo(readTimeout) >= 0) {
            throw new IllegalStateException("PARKIO_EXPO_PUSH_CONNECT_TIMEOUT (" + connectTimeout
                    + ") must be shorter than PARKIO_EXPO_PUSH_READ_TIMEOUT (" + readTimeout + ")");
        }
    }

    @Bean
    RestClient expoPushRestClient(
            RestClient.Builder builder,
            ObjectProvider<HttpClientSettings> httpClientSettings,
            @Value("${parkio.notification.delivery.push.expo.base-url:https://exp.host/--/api/v2}") String baseUrl) {
        return builder
                .requestFactory(requestFactory(
                        httpClientSettings.getIfAvailable(HttpClientSettings::defaults), connectTimeout, readTimeout))
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .build();
    }

    /**
     * The Expo transport: the JDK client with both timeouts, on top of the shared {@code spring.http.clients}
     * settings. It is the JDK client whatever factory the other clients use, because the sender treats only the
     * JDK's {@code HttpConnectTimeoutException} as a connect timeout that is safe to retry. The JDK connect timeout
     * also covers the TLS handshake but not the DNS lookup, which the JDK does before it starts the connect timer; the
     * read timeout covers the whole exchange, lookup and response body included (#641).
     */
    public static ClientHttpRequestFactory requestFactory(
            HttpClientSettings settings, Duration connectTimeout, Duration readTimeout) {
        return ClientHttpRequestFactoryBuilder.jdk().build(settings.withTimeouts(connectTimeout, readTimeout));
    }

    private static void requireBounded(String property, Duration value, Duration max) {
        if (value == null || value.isNegative() || value.isZero() || value.compareTo(max) > 0) {
            throw new IllegalStateException(property + " must be greater than 0 and at most " + max + ", got " + value);
        }
    }
}
