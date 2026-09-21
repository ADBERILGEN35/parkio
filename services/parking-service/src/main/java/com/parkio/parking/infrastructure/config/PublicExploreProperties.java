package com.parkio.parking.infrastructure.config;

import com.parkio.parking.externalsource.PublicExplorePublicationPolicy;
import com.parkio.parking.externalsource.PublicExplorePublicationPolicy.ReviewedPublicFamily;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Fail-closed configuration for the anonymous read-only product surface.
 *
 * <p>Allowed families are parsed through {@link PublicExplorePublicationPolicy}: only reviewed
 * families (IZUM, ISPARK, IZELMAN, OSM) may be configured. Empty allowlist → no municipal public
 * rows. Ingestion enablement is a separate gate and does not imply publication.
 */
@ConfigurationProperties(prefix = "parkio.public-explore")
public class PublicExploreProperties {
    private boolean enabled;
    private List<String> allowedSourceFamilies = List.of();
    private Set<ReviewedPublicFamily> reviewedFamilies = Set.of();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getAllowedSourceFamilies() {
        return allowedSourceFamilies;
    }

    public void setAllowedSourceFamilies(List<String> allowedSourceFamilies) {
        if (allowedSourceFamilies == null) {
            this.allowedSourceFamilies = List.of();
            this.reviewedFamilies = Set.of();
            return;
        }
        Set<ReviewedPublicFamily> parsed =
                PublicExplorePublicationPolicy.parseAllowedFamilies(allowedSourceFamilies);
        this.reviewedFamilies = parsed;
        this.allowedSourceFamilies = parsed.stream().map(Enum::name).sorted().toList();
    }

    /** True when the reviewed IZUM family is on the publication allowlist. */
    public boolean isIzumAllowed() {
        return reviewedFamilies.contains(ReviewedPublicFamily.IZUM);
    }

    /** True when the reviewed ISPARK family is on the publication allowlist. */
    public boolean isIsparkAllowed() {
        return reviewedFamilies.contains(ReviewedPublicFamily.ISPARK);
    }

    public boolean hasAllowedSources() {
        return !reviewedFamilies.isEmpty();
    }

    public Set<ReviewedPublicFamily> reviewedFamilies() {
        return reviewedFamilies;
    }

    /** Validated source keys for repository queries; never raw HTTP input. */
    public Set<String> resolvedSourceKeys() {
        return PublicExplorePublicationPolicy.sourceKeysFor(reviewedFamilies);
    }
}
