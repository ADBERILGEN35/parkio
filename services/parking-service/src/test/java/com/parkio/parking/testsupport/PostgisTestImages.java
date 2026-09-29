package com.parkio.parking.testsupport;

import org.testcontainers.utility.DockerImageName;

/**
 * Shared PostGIS Testcontainers image selection for parking-service ITs.
 *
 * <p>Default remains the repository baseline {@code postgis/postgis:16-3.4}. Override with
 * {@code -Dparkio.postgis.image=<image>} (optionally with digest) for Mode A parity runs
 * against a newer pinned PostGIS build. Do not use floating {@code latest}.
 */
public final class PostgisTestImages {

    public static final String DEFAULT_IMAGE = "postgis/postgis:16-3.4";
    public static final String SYSTEM_PROPERTY = "parkio.postgis.image";

    private PostgisTestImages() {}

    public static String imageReference() {
        String override = System.getProperty(SYSTEM_PROPERTY);
        if (override == null || override.isBlank()) {
            return DEFAULT_IMAGE;
        }
        return override.trim();
    }

    public static DockerImageName dockerImageName() {
        return DockerImageName.parse(imageReference()).asCompatibleSubstituteFor("postgres");
    }
}
