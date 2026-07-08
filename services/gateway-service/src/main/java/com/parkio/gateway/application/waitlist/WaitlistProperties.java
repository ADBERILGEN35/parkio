package com.parkio.gateway.application.waitlist;

import jakarta.validation.Valid;
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

    @Valid
    @NotNull
    private RateLimit ipRateLimit = new RateLimit();

    @Valid
    @NotNull
    private RateLimit emailRateLimit = new RateLimit();

    public String getHashSecret() {
        return hashSecret;
    }

    public void setHashSecret(String hashSecret) {
        this.hashSecret = hashSecret;
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
}
