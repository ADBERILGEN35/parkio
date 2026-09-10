package com.parkio.parking.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "parkio.municipal.registry")
public class RegistryProperties {
    private boolean candidateGenerationEnabled;
    private boolean reviewApiEnabled;
    private boolean reviewedLinkingEnabled;
    private boolean automaticLinkingEnabled;
    /**
     * DATA-WP-11: public nearby/detail provenance enrichment. Canonical default true.
     * Production profile ({@code application-prod.yml}) pins the env default false.
     * Independent of ingest-write, candidate generation, review API, and linking.
     */
    private boolean provenancePublicationEnabled = true;
    /**
     * DATA-WP-10 kill-switch for ingest provenance writes. Default true so successful
     * İZUM/OSM ingest records allow-listed field ownership. Does not control publication.
     */
    private boolean provenanceIngestWriteEnabled = true;

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

    public boolean isProvenanceIngestWriteEnabled() {
        return provenanceIngestWriteEnabled;
    }

    public void setProvenanceIngestWriteEnabled(boolean provenanceIngestWriteEnabled) {
        this.provenanceIngestWriteEnabled = provenanceIngestWriteEnabled;
    }
}
