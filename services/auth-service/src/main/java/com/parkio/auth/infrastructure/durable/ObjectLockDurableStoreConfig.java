package com.parkio.auth.infrastructure.durable;

import com.parkio.auth.application.durable.DurableEvidenceException;
import com.parkio.auth.application.durable.EvidenceTrust;
import com.parkio.auth.application.durable.TrustedKey;
import com.parkio.auth.infrastructure.persistence.PostgresDatabaseIdentity;
import io.minio.MinioClient;
import io.minio.messages.RetentionMode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import okhttp3.OkHttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wires {@link ObjectLockDurableErasureRecordStore} only when
 * {@code parkio.privacy.account-erasure.durable-store.object-lock.enabled=true}. Startup fails
 * when a setting is missing, the trust document is unreadable or invalid, the signing key is
 * not in it or may not sign now, the trust document is pinned to another database than the one
 * this service uses, or the bucket has no object lock. Errors name settings and key ids, never
 * secret values. Without this store, enabling durable recording still fails with
 * DURABLE_RECORDING_UNAVAILABLE (unchanged).
 */
@Configuration
@EnableConfigurationProperties(ObjectLockStoreProperties.class)
@ConditionalOnProperty(name = "parkio.privacy.account-erasure.durable-store.object-lock.enabled", havingValue = "true")
class ObjectLockDurableStoreConfig {

    private static final String PREFIX = "parkio.privacy.account-erasure.durable-store.object-lock";

    @Bean
    ObjectLockDurableErasureRecordStore objectLockDurableErasureRecordStore(ObjectLockStoreProperties properties,
                                                                           Clock clock, JdbcTemplate jdbc) {
        List<String> problems = properties.problems();
        if (!problems.isEmpty()) {
            throw new IllegalStateException(PREFIX + " is enabled but incomplete: " + String.join(", ", problems));
        }
        EvidenceTrust trust = trust(properties.getTrustFile());
        TrustedKey signingKey = trust.key(properties.getProducerKeyId()).orElseThrow(() -> new IllegalStateException(
                PREFIX + ".producer-key-id " + properties.getProducerKeyId() + " is not in the trust document"));
        if (!signingKey.signsAt(clock.instant())) {
            throw new IllegalStateException(PREFIX + ": producer key " + signingKey.keyId()
                    + " is retired or outside its signing window");
        }
        String database = PostgresDatabaseIdentity.of(jdbc);
        if (!database.equals(trust.databaseIdentity())) {
            throw new IllegalStateException(PREFIX + ": the trust document is pinned to "
                    + trust.databaseIdentity() + ", but this service uses " + database);
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
        return new ObjectLockDurableErasureRecordStore(bucket, trust, signingKey.keyId(),
                RetentionMode.valueOf(properties.getRetentionMode()), properties.getRetention(), clock);
    }

    private static EvidenceTrust trust(String file) {
        byte[] document;
        try {
            document = Files.readAllBytes(Path.of(file));
        } catch (IOException | RuntimeException ex) {
            throw new IllegalStateException(PREFIX + ".trust-file is not readable: " + ex.getClass().getSimpleName());
        }
        try {
            return EvidenceTrust.parse(document);
        } catch (DurableEvidenceException ex) {
            throw new IllegalStateException(PREFIX + ".trust-file is invalid: " + ex.getMessage());
        }
    }
}
