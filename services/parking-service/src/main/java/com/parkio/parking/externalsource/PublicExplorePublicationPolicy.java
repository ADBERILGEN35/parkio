package com.parkio.parking.externalsource;

import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Authoritative anonymous Public Explore publication policy.
 *
 * <p>Ingestion / authenticated municipal APIs are separate gates. A source becomes
 * anonymously public only when it is {@linkplain ReviewedPublicFamily code-supported and
 * reviewed} AND explicitly listed in configuration.
 *
 * <p>Unknown or known-but-unreviewed family tokens fail closed at configuration time.
 */
public final class PublicExplorePublicationPolicy {
    /**
     * Families explicitly reviewed for anonymous Public Explore publication.
     * Architecture may ingest other municipal providers; they are not auto-published here.
     */
    public enum ReviewedPublicFamily {
        IZUM(MunicipalSourceIdentity.IZUM),
        ISPARK(MunicipalSourceIdentity.ISPARK);

        private final Set<String> sourceKeys;

        ReviewedPublicFamily(String... sourceKeys) {
            this.sourceKeys = Set.of(sourceKeys);
        }

        public Set<String> sourceKeys() {
            return sourceKeys;
        }

        public static Optional<ReviewedPublicFamily> parseToken(String raw) {
            if (raw == null || raw.isBlank()) {
                return Optional.empty();
            }
            String token = raw.trim().toUpperCase(Locale.ROOT);
            for (ReviewedPublicFamily family : values()) {
                if (family.name().equals(token)) {
                    return Optional.of(family);
                }
            }
            return Optional.empty();
        }
    }

    /** Known municipal family tokens that exist in code but are not reviewed for anonymous publish. */
    private static final Set<String> KNOWN_UNREVIEWED_FAMILY_TOKENS = Set.of(
            "ANPARK", "KONYA", "KAYSERI", "IZELMAN", "OSM", "OPENSTREETMAP", "FAKE_TEST", "UNKNOWN");

    private PublicExplorePublicationPolicy() {}

    public static Set<ReviewedPublicFamily> reviewedFamilies() {
        return EnumSet.allOf(ReviewedPublicFamily.class);
    }

    /**
     * Validates and normalizes configured family tokens.
     *
     * @throws IllegalArgumentException for unknown or unreviewed tokens (fail closed)
     */
    public static Set<ReviewedPublicFamily> parseAllowedFamilies(Collection<String> rawTokens) {
        if (rawTokens == null || rawTokens.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<ReviewedPublicFamily> accepted = new LinkedHashSet<>();
        for (String raw : rawTokens) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String token = raw.trim().toUpperCase(Locale.ROOT);
            Optional<ReviewedPublicFamily> reviewed = ReviewedPublicFamily.parseToken(token);
            if (reviewed.isPresent()) {
                accepted.add(reviewed.get());
                continue;
            }
            if (KNOWN_UNREVIEWED_FAMILY_TOKENS.contains(token)) {
                throw new IllegalArgumentException(
                        "Public explore rejects unreviewed source family: " + token);
            }
            throw new IllegalArgumentException(
                    "Public explore unsupported source family: " + token);
        }
        return Set.copyOf(accepted);
    }

    public static Set<String> sourceKeysFor(Collection<ReviewedPublicFamily> families) {
        if (families == null || families.isEmpty()) {
            return Set.of();
        }
        return families.stream()
                .filter(Objects::nonNull)
                .flatMap(family -> family.sourceKeys().stream())
                .collect(Collectors.toUnmodifiableSet());
    }
}
