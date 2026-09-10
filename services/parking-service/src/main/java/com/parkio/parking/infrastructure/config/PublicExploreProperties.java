package com.parkio.parking.infrastructure.config;

import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Fail-closed configuration for the anonymous read-only product surface. */
@ConfigurationProperties(prefix = "parkio.public-explore")
public class PublicExploreProperties {
    private boolean enabled;
    private List<String> allowedSourceFamilies = List.of();

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
            return;
        }
        List<String> normalized = allowedSourceFamilies.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .toList();
        if (normalized.stream().anyMatch(value -> !"IZUM".equals(value))) {
            throw new IllegalArgumentException(
                    "Public explore supports only the reviewed IZUM source family");
        }
        this.allowedSourceFamilies = List.copyOf(normalized);
    }

    public boolean isIzumAllowed() {
        return allowedSourceFamilies.contains("IZUM");
    }
}
