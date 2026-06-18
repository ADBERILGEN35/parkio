package com.parkio.auth.infrastructure.notification;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/** Wires provider-specific transactional email infrastructure. */
@Configuration
@EnableConfigurationProperties(TransactionalEmailProperties.class)
public class EmailDeliveryConfig {

    private final TransactionalEmailProperties properties;
    private final Environment environment;
    private final boolean verificationTokenLogging;
    private final boolean resetTokenLogging;

    public EmailDeliveryConfig(TransactionalEmailProperties properties,
                               Environment environment,
                               @org.springframework.beans.factory.annotation.Value(
                                       "${parkio.security.email-verification.log-token:false}")
                                       boolean verificationTokenLogging,
                               @org.springframework.beans.factory.annotation.Value(
                                       "${parkio.security.password-reset.log-token:false}")
                                       boolean resetTokenLogging) {
        this.properties = properties;
        this.environment = environment;
        this.verificationTokenLogging = verificationTokenLogging;
        this.resetTokenLogging = resetTokenLogging;
    }

    @PostConstruct
    void validate() {
        TransactionalEmailProperties.Provider provider = properties.getProvider();
        if (isProductionProfile()) {
            if (provider != TransactionalEmailProperties.Provider.RESEND) {
                throw new IllegalStateException("Production auth-service requires parkio.email.provider=resend");
            }
            if (verificationTokenLogging || resetTokenLogging) {
                throw new IllegalStateException("Production auth-service must not log raw email tokens");
            }
        }
        if (provider == TransactionalEmailProperties.Provider.RESEND) {
            requireText(properties.getResend().getApiKey(), "PARKIO_RESEND_API_KEY");
            requireText(properties.getFrom(), "PARKIO_EMAIL_FROM");
        }
    }

    @Bean
    RestClient resendRestClient(RestClient.Builder builder) {
        return builder
                .baseUrl(properties.getResend().getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getResend().getApiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    private boolean isProductionProfile() {
        return Arrays.stream(environment.getActiveProfiles())
                .map(profile -> profile.toLowerCase(java.util.Locale.ROOT))
                .anyMatch(profile -> profile.equals("prod") || profile.equals("production"));
    }

    private static void requireText(String value, String envName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(envName + " must be configured for Resend email delivery");
        }
    }
}
