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

    @Valid
    @NotNull
    private RateLimit ipRateLimit = new RateLimit();

    @Valid
    @NotNull
    private RateLimit emailRateLimit = new RateLimit();

    @Valid
    @NotNull
    private Email email = new Email();

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

        /** logging (default) or resend */
        @NotBlank
        private String provider = "logging";

        private String from = "";

        private String replyTo = "";

        private String resendApiKey = "";

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
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
    }
}
