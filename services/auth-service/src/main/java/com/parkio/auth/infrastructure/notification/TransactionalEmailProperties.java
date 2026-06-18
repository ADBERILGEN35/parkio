package com.parkio.auth.infrastructure.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Transactional email provider settings for verification and account recovery. */
@ConfigurationProperties(prefix = "parkio.email")
public class TransactionalEmailProperties {

    private Provider provider = Provider.LOGGING;
    private String from;
    private String replyTo;
    private Resend resend = new Resend();

    public Provider getProvider() {
        return provider;
    }

    public void setProvider(Provider provider) {
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

    public Resend getResend() {
        return resend;
    }

    public void setResend(Resend resend) {
        this.resend = resend;
    }

    public enum Provider {
        LOGGING,
        RESEND
    }

    public static class Resend {

        private String apiKey;
        private String baseUrl = "https://api.resend.com";

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }
}
