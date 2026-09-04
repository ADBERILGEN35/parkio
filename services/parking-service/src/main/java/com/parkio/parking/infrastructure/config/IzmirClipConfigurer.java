package com.parkio.parking.infrastructure.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.externalsource.osm.IzmirBoundaryAssetValidator;
import com.parkio.parking.externalsource.osm.IzmirClip;
import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Configures {@link IzmirClip} membership from municipal OSM properties.
 * Missing operator boundary falls back to the verified admin envelope (CI-safe).
 */
@Component
public class IzmirClipConfigurer {
    private static final Logger log = LoggerFactory.getLogger(IzmirClipConfigurer.class);

    private final MunicipalSourceProperties properties;
    private final ObjectMapper objectMapper;

    public IzmirClipConfigurer(MunicipalSourceProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void configure() {
        MunicipalSourceProperties.Osm osm = properties.getOsm();
        String version = osm.getClipVersion() == null || osm.getClipVersion().isBlank()
                ? IzmirClip.CLIP_VERSION
                : osm.getClipVersion().trim();

        if (IzmirClip.BBOX_CLIP_VERSION.equals(version)) {
            IzmirClip.resetToLegacyBbox();
            log.info("izmir_clip_configured mode=legacy-bbox clipVersion={}", version);
            return;
        }

        Path dir = osm.getBoundaryDir() == null || osm.getBoundaryDir().isBlank()
                ? null
                : Path.of(osm.getBoundaryDir()).toAbsolutePath().normalize();
        if (dir == null) {
            IzmirClip.resetToAdminEnvelope();
            log.info("izmir_clip_configured mode=admin-envelope clipVersion={} reason=no_boundary_dir", version);
            return;
        }

        Path geojson = dir.resolve(osm.getBoundaryGeojsonFilename());
        if (!Files.isRegularFile(geojson)) {
            IzmirClip.resetToAdminEnvelope();
            log.warn("izmir_clip_configured mode=admin-envelope clipVersion={} reason=missing_geojson path={}",
                    version, geojson);
            return;
        }
        try {
            byte[] bytes = Files.readAllBytes(geojson);
            String expected = osm.getBoundaryGeojsonSha256();
            if (expected != null && !expected.isBlank()) {
                String actual = IzmirBoundaryAssetValidator.sha256(bytes);
                if (!expected.equalsIgnoreCase(actual)) {
                    throw new IllegalStateException("boundary geojson checksum mismatch");
                }
            }
            JsonNode root = objectMapper.readTree(bytes);
            JsonNode geometry = root.path("features").path(0).path("geometry");
            if (geometry.isMissingNode() && "Feature".equals(root.path("type").asText())) {
                geometry = root.path("geometry");
            }
            List<double[][]> rings = IzmirClip.exteriorRingsFromCoordinates(geometry);
            if (rings.isEmpty()) {
                throw new IllegalStateException("boundary geojson has no polygonal exterior rings");
            }
            IzmirClip.configure(IzmirClip.Membership.polygon(rings));
            log.info("izmir_clip_configured mode=polygon clipVersion={} path={} rings={}",
                    version, geojson, rings.size());
        } catch (Exception ex) {
            IzmirClip.resetToAdminEnvelope();
            log.warn("izmir_clip_configured mode=admin-envelope clipVersion={} reason=load_failed err={}",
                    version, ex.toString());
        }
    }
}
