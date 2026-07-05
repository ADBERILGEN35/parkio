package com.parkio.notification.infrastructure.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Configuration
@ConditionalOnProperty(name = "parkio.notification.delivery.push.provider", havingValue = "expo")
public class ExpoPushConfig {

    @Value("${parkio.notification.delivery.push.expo.access-token:}")
    private String accessToken;

    @PostConstruct
    void validate() {
        if (!StringUtils.hasText(accessToken)) {
            throw new IllegalStateException(
                    "PARKIO_EXPO_ACCESS_TOKEN must be configured when parkio.notification.delivery.push.provider=expo");
        }
    }

    @Bean
    RestClient expoPushRestClient(
            RestClient.Builder builder,
            @Value("${parkio.notification.delivery.push.expo.base-url:https://exp.host/--/api/v2}") String baseUrl,
            @Value("${parkio.notification.delivery.push.expo.access-token}") String accessToken) {
        return builder
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .build();
    }
}