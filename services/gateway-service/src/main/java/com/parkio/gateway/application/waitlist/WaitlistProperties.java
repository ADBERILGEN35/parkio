package com.parkio.gateway.application.waitlist;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "parkio.waitlist")
public class WaitlistProperties {

    /**
     * When false (default), new waitlist registrations and confirmation-email
     * resends are rejected before database mutation or provider dispatch.
     * Confirmation of already-issued tokens and withdrawal remain available.
     * Bound at process start — changing the env var requires a gateway restart.
     */
    private boolean admissionsEnabled = false;

    /**
     * When true, new subscription requests must include a valid {@code fullName}.
     * Default false so the pre-name marketing form on Hostinger remains compatible
     * until the Hostinger upload and this flag flip are coordinated.
     */
    private boolean fullNameRequired = false;

    @NotBlank
    private String hashSecret;

    @NotBlank
    private String confirmBaseUrl = "https://parkio.dev/waitlist/confirm/";

    @NotBlank
    private String withdrawBaseUrl = "https://parkio.dev/waitlist/unsubscribe/";

    @NotNull
    private Duration tokenTtl = Duration.ofHours(48);

    @Min(1)
    private int maxResends = 5;

    @NotNull
    private Duration resendCooldown = Duration.ofMinutes(5);

    /**
     * How far ahead of gateway UTC a client-asserted consentTimestamp may be.
     * Covers typical browser/NTP skew and request latency without accepting
     * arbitrary future dates.
     */
    @NotNull
    private Duration consentMaxFutureSkew = Duration.ofMinutes(2);

    /**
     * How far in the past a client-asserted consentTimestamp may be relative to
     * gateway UTC. Beyond this, treat as invalid (stale form / replay).
     */
    @NotNull
    private Duration consentMaxPastAge = Duration.ofDays(7);

    @Valid
    @NotNull
    private RateLimit ipRateLimit = new RateLimit();

    @Valid
    @NotNull
    private RateLimit emailRateLimit = new RateLimit();

    @Valid
    @NotNull
    private Email email = new Email();

    public boolean isAdmissionsEnabled() {
        return admissionsEnabled;
    }

    public void setAdmissionsEnabled(boolean admissionsEnabled) {
        this.admissionsEnabled = admissionsEnabled;
    }

    public boolean isFullNameRequired() {
        return fullNameRequired;
    }

    public void setFullNameRequired(boolean fullNameRequired) {
        this.fullNameRequired = fullNameRequired;
    }

    public String getHashSecret() {
        return hashSecret;
    }

    public void setHashSecret(String hashSecret) {
        this.hashSecret = hashSecret;
    }

    public String getConfirmBaseUrl() {
        return confirmBaseUrl;
    }

    public void setConfirmBaseUrl(String confirmBaseUrl) {
        this.confirmBaseUrl = confirmBaseUrl;
    }

    public String getWithdrawBaseUrl() {
        return withdrawBaseUrl;
    }

    public void setWithdrawBaseUrl(String withdrawBaseUrl) {
        this.withdrawBaseUrl = withdrawBaseUrl;
    }

    public Duration getTokenTtl() {
        return tokenTtl;
    }

    public void setTokenTtl(Duration tokenTtl) {
        this.tokenTtl = tokenTtl;
    }

    public int getMaxResends() {
        return maxResends;
    }

    public void setMaxResends(int maxResends) {
        this.maxResends = maxResends;
    }

    public Duration getResendCooldown() {
        return resendCooldown;
    }

    public void setResendCooldown(Duration resendCooldown) {
        this.resendCooldown = resendCooldown;
    }

    public Duration getConsentMaxFutureSkew() {
        return consentMaxFutureSkew;
    }

    public void setConsentMaxFutureSkew(Duration consentMaxFutureSkew) {
        this.consentMaxFutureSkew = consentMaxFutureSkew;
    }

    public Duration getConsentMaxPastAge() {
        return consentMaxPastAge;
    }

    public void setConsentMaxPastAge(Duration consentMaxPastAge) {
        this.consentMaxPastAge = consentMaxPastAge;
    }

    public RateLimit getIpRateLimit() {
        return ipRateLimit;
    }

    public void setIpRateLimit(RateLimit ipRateLimit) {
        this.ipRateLimit = ipRateLimit;
    }

    public RateLimit getEmailRateLimit() {
        return emailRateLimit;
    }

    public void setEmailRateLimit(RateLimit emailRateLimit) {
        this.emailRateLimit = emailRateLimit;
    }

    public Email getEmail() {
        return email;
    }

    public void setEmail(Email email) {
        this.email = email;
    }

    public static class RateLimit {

        @Min(1)
        private int maxAttempts = 10;

        @NotNull
        private Duration window = Duration.ofHours(1);

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Duration getWindow() {
            return window;
        }

        public void setWindow(Duration window) {
            this.window = window;
        }
    }

    public static class Email {

        /** logging (default for local/test) or resend */
        @NotBlank
        private String provider = "logging";

        /**
         * When false, provider=logging refuses to start. Production must set
         * PARKIO_WAITLIST_ALLOW_LOGGING_PROVIDER=false and use provider=resend.
         */
        private boolean allowLoggingProvider = true;

        private String from = "";

        private String replyTo = "";

        private String resendApiKey = "";

        /** Override only for isolated mocks; production keeps the Resend API host. */
        private String resendBaseUrl = "https://api.resend.com";

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public boolean isAllowLoggingProvider() {
            return allowLoggingProvider;
        }

        public void setAllowLoggingProvider(boolean allowLoggingProvider) {
            this.allowLoggingProvider = allowLoggingProvider;
        }

        public String getFrom() {
            return from;
        }

        public void setFrom(String from) {
            this.from = from;
        }

        public String getReplyTo() {
            return replyTo;
        }

        public void setReplyTo(String replyTo) {
            this.replyTo = replyTo;
        }

        public String getResendApiKey() {
            return resendApiKey;
        }

        public void setResendApiKey(String resendApiKey) {
            this.resendApiKey = resendApiKey;
        }

        public String getResendBaseUrl() {
            return resendBaseUrl;
        }

        public void setResendBaseUrl(String resendBaseUrl) {
            this.resendBaseUrl = resendBaseUrl;
        }
    }
}
