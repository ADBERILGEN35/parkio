package com.parkio.auth.infrastructure.notification;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
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
    private final String verificationUrl;
    private final String resetUrl;

    public EmailDeliveryConfig(TransactionalEmailProperties properties,
                               Environment environment,
                               @org.springframework.beans.factory.annotation.Value(
                                       "${parkio.security.email-verification.log-token:false}")
                                       boolean verificationTokenLogging,
                               @org.springframework.beans.factory.annotation.Value(
                                       "${parkio.security.password-reset.log-token:false}")
                                       boolean resetTokenLogging,
                               @org.springframework.beans.factory.annotation.Value(
                                       "${parkio.security.email-verification.url:http://localhost:5173/verify-email}")
                                       String verificationUrl,
                               @org.springframework.beans.factory.annotation.Value(
                                       "${parkio.security.password-reset.url:http://localhost:5173/reset-password}")
                                       String resetUrl) {
        this.properties = properties;
        this.environment = environment;
        this.verificationTokenLogging = verificationTokenLogging;
        this.resetTokenLogging = resetTokenLogging;
        this.verificationUrl = verificationUrl;
        this.resetUrl = resetUrl;
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
        // A real email provider (or a production-like profile) must never mail out
        // local-dev links: fail startup instead of silently sending localhost URLs.
        if (isProductionProfile() || provider == TransactionalEmailProperties.Provider.RESEND) {
            requirePublicWebUrl(verificationUrl,
                    "parkio.security.email-verification.url (PARKIO_WEB_BASE_URL / PARKIO_EMAIL_VERIFICATION_URL)");
            requirePublicWebUrl(resetUrl,
                    "parkio.security.password-reset.url (PARKIO_WEB_BASE_URL / PARKIO_PASSWORD_RESET_URL)");
        }
    }

    @Bean
    RestClient resendRestClient(RestClient.Builder builder) {
        TransactionalEmailProperties.Resend resend = properties.getResend();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(resend.getConnectTimeout());
        requestFactory.setReadTimeout(resend.getReadTimeout());
        return builder
                .baseUrl(resend.getBaseUrl())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + resend.getApiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    private boolean isProductionProfile() {
        return Arrays.stream(environment.getActiveProfiles())
                .map(profile -> profile.toLowerCase(java.util.Locale.ROOT))
                .anyMatch(profile -> profile.equals("prod")
                        || profile.equals("production")
                        || profile.equals("hosted-beta"));
    }

    private static void requireText(String value, String envName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(envName + " must be configured for Resend email delivery");
        }
    }

    private static void requirePublicWebUrl(String url, String property) {
        if (!StringUtils.hasText(url)) {
            throw new IllegalStateException(property + " must be configured for transactional email links");
        }
        java.net.URI uri;
        try {
            uri = java.net.URI.create(url);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(property + " is not a valid URL: " + url, ex);
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
        if (isLocalHost(host)) {
            throw new IllegalStateException(
                    property + " points at a local-dev host; set the public web origin: " + url);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalStateException(
                    property + " must be an https URL when transactional email is live: " + url);
        }
    }

    private static boolean isLocalHost(String host) {
        return host.isEmpty()
                || host.equals("localhost")
                || host.equals("0.0.0.0")
                || host.equals("10.0.2.2")
                || host.startsWith("127.")
                || host.endsWith(".local");
    }
}
