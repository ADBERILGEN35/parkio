package com.parkio.media.infrastructure.config;

import com.parkio.media.application.MediaAccessUrlPolicy;
import com.parkio.media.application.MediaUploadConstraints;
import com.parkio.media.application.port.MediaScanner;
import com.parkio.media.infrastructure.scanner.ClamavMediaScanner;
import com.parkio.media.infrastructure.scanner.NoOpMediaScanner;
import io.minio.MinioClient;
import java.time.Clock;
import java.util.Set;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.StringUtils;

/**
 * Infrastructure wiring: a system-UTC {@link Clock}, MinIO clients built from
 * configuration (internal operations vs browser-facing presign host), the
 * application's {@link MediaUploadConstraints} derived from properties (so the
 * application layer stays free of Spring config types), and scheduling for the
 * outbox relay poller.
 */
@Configuration
@EnableConfigurationProperties(MediaProperties.class)
@EnableScheduling
public class MediaInfrastructureConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Internal MinIO client — PUT, stat, DELETE against the container/cluster
     * endpoint (e.g. {@code http://minio:9000} in compose).
     */
    @Bean
    @Primary
    @Qualifier("internalMinioClient")
    public MinioClient internalMinioClient(MediaProperties properties) {
        return buildMinioClient(properties.getStorage().getEndpoint(), properties.getStorage());
    }

    /**
     * Presign MinIO client — generates GET URLs whose {@code Host} matches what
     * the browser will use (e.g. {@code http://localhost:9000}). SigV4 signs that
     * host; it cannot be rewritten after signing.
     */
    @Bean
    @Qualifier("presignMinioClient")
    public MinioClient presignMinioClient(MediaProperties properties) {
        MediaProperties.Storage storage = properties.getStorage();
        String publicEndpoint = StringUtils.hasText(storage.getPublicEndpoint())
                ? storage.getPublicEndpoint()
                : storage.getEndpoint();
        return buildMinioClient(publicEndpoint, storage);
    }

    private static MinioClient buildMinioClient(String endpoint, MediaProperties.Storage storage) {
        MinioClient.Builder builder = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(storage.getAccessKey(), storage.getSecretKey())
                .httpClient(minioHttpClient(storage));
        if (StringUtils.hasText(storage.getRegion())) {
            builder.region(storage.getRegion());
        }
        return builder.build();
    }

    private static OkHttpClient minioHttpClient(MediaProperties.Storage storage) {
        return new OkHttpClient.Builder()
                .connectTimeout(storage.getConnectTimeout())
                .readTimeout(storage.getReadTimeout())
                .writeTimeout(storage.getWriteTimeout())
                .callTimeout(storage.getCallTimeout())
                .build();
    }

    @Bean
    public MediaUploadConstraints mediaUploadConstraints(MediaProperties properties) {
        return new MediaUploadConstraints(
                Set.copyOf(properties.getAllowedContentTypes()),
                properties.getMaxFileSize().toBytes(),
                properties.getMaxImageWidth(),
                properties.getMaxImageHeight(),
                properties.getMaxImagePixels());
    }

    @Bean
    public MediaAccessUrlPolicy mediaAccessUrlPolicy(MediaProperties properties) {
        return new MediaAccessUrlPolicy(properties.getAccessUrlTtl());
    }

    /**
     * Real ClamAV scanner when {@code parkio.media.scanner.enabled=true} (default —
     * production / hosted beta), or a pass-through scanner when disabled (local dev
     * without a clamd container, and tests). Disabling is logged loudly by
     * {@link NoOpMediaScanner}.
     */
    @Bean
    public MediaScanner mediaScanner(MediaProperties properties) {
        MediaProperties.Scanner scanner = properties.getScanner();
        if (!scanner.isEnabled()) {
            return new NoOpMediaScanner();
        }
        return new ClamavMediaScanner(scanner.getHost(), scanner.getPort(),
                scanner.getConnectTimeout(), scanner.getReadTimeout());
    }
}
