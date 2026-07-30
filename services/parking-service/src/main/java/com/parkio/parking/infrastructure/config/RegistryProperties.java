package com.parkio.parking.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "parkio.municipal.registry")
public class RegistryProperties {
    private boolean candidateGenerationEnabled;
    private boolean reviewApiEnabled;
    private boolean reviewedLinkingEnabled;
    private boolean automaticLinkingEnabled;
    private boolean provenancePublicationEnabled;

    public boolean isCandidateGenerationEnabled() {
        return candidateGenerationEnabled;
    }

    public void setCandidateGenerationEnabled(boolean candidateGenerationEnabled) {
        this.candidateGenerationEnabled = candidateGenerationEnabled;
    }

    public boolean isReviewApiEnabled() {
        return reviewApiEnabled;
    }

    public void setReviewApiEnabled(boolean reviewApiEnabled) {
        this.reviewApiEnabled = reviewApiEnabled;
    }

    public boolean isReviewedLinkingEnabled() {
        return reviewedLinkingEnabled;
    }

    public void setReviewedLinkingEnabled(boolean reviewedLinkingEnabled) {
        this.reviewedLinkingEnabled = reviewedLinkingEnabled;
    }

    public boolean isAutomaticLinkingEnabled() {
        return false;
    }

    public void setAutomaticLinkingEnabled(boolean automaticLinkingEnabled) {
        if (automaticLinkingEnabled) {
            throw new IllegalArgumentException("Automatic municipal registry linking is prohibited");
        }
        this.automaticLinkingEnabled = false;
    }

    public boolean isProvenancePublicationEnabled() {
        return provenancePublicationEnabled;
    }

    public void setProvenancePublicationEnabled(boolean provenancePublicationEnabled) {
        this.provenancePublicationEnabled = provenancePublicationEnabled;
    }
}
