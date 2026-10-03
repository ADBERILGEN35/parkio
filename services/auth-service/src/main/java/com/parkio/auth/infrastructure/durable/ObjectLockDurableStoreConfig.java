package com.parkio.auth.infrastructure.durable;

import com.parkio.auth.application.durable.ProducerKey;
import io.minio.MinioClient;
import io.minio.messages.RetentionMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import okhttp3.OkHttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires {@link ObjectLockDurableErasureRecordStore} only when
 * {@code parkio.privacy.account-erasure.durable-store.object-lock.enabled=true}. Startup fails
 * when a setting is missing or the bucket has no object lock. Without this store, enabling
 * durable recording still fails with DURABLE_RECORDING_UNAVAILABLE (unchanged).
 */
@Configuration
@EnableConfigurationProperties(ObjectLockStoreProperties.class)
@ConditionalOnProperty(name = "parkio.privacy.account-erasure.durable-store.object-lock.enabled", havingValue = "true")
class ObjectLockDurableStoreConfig {

    @Bean
    ObjectLockDurableErasureRecordStore objectLockDurableErasureRecordStore(ObjectLockStoreProperties properties,
                                                                           Clock clock) {
        List<String> problems = properties.problems();
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "parkio.privacy.account-erasure.durable-store.object-lock is enabled but incomplete: "
                            + String.join(", ", problems));
        }
        MinioClient client = MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .region(properties.getRegion())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .httpClient(new OkHttpClient.Builder()
                        .connectTimeout(properties.getConnectTimeout())
                        .callTimeout(properties.getCallTimeout())
                        .build())
                .build();
        ObjectLockBucket bucket = new ObjectLockBucket(client, properties.getBucket());
        bucket.requireObjectLock();
        return new ObjectLockDurableErasureRecordStore(bucket, properties.getDatabaseIdentity(),
                new ProducerKey(properties.getProducerId(), properties.getProducerKey().getBytes(StandardCharsets.UTF_8)),
                RetentionMode.valueOf(properties.getRetentionMode()), properties.getRetention(), clock);
    }
}
