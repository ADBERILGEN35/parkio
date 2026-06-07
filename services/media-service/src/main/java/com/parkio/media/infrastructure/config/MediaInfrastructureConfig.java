package com.parkio.media.infrastructure.config;

import com.parkio.media.application.MediaUploadConstraints;
import io.minio.MinioClient;
import java.time.Clock;
import java.util.Set;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Infrastructure wiring: a system-UTC {@link Clock}, the MinIO client built from
 * configuration, and the application's {@link MediaUploadConstraints} derived from
 * properties (so the application layer stays free of Spring config types).
 */
@Configuration
@EnableConfigurationProperties(MediaProperties.class)
public class MediaInfrastructureConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public MinioClient minioClient(MediaProperties properties) {
        MediaProperties.Storage storage = properties.getStorage();
        MinioClient.Builder builder = MinioClient.builder()
                .endpoint(storage.getEndpoint())
                .credentials(storage.getAccessKey(), storage.getSecretKey());
        if (StringUtils.hasText(storage.getRegion())) {
            builder.region(storage.getRegion());
        }
        return builder.build();
    }

    @Bean
    public MediaUploadConstraints mediaUploadConstraints(MediaProperties properties) {
        return new MediaUploadConstraints(
                Set.copyOf(properties.getAllowedContentTypes()),
                properties.getMaxFileSize().toBytes());
    }
}
