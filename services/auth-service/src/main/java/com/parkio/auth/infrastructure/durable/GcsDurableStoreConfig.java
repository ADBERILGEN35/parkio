package com.parkio.auth.infrastructure.durable;

import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.parkio.auth.application.durable.DurableEvidenceException;
import com.parkio.auth.application.durable.EvidenceTrust;
import com.parkio.auth.application.durable.TrustedKey;
import com.parkio.auth.infrastructure.persistence.PostgresDatabaseIdentity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wires {@link ObjectLockDurableErasureRecordStore} on a GCS Object Retention Lock bucket only when
 * {@code parkio.privacy.account-erasure.durable-store.gcs.enabled=true}. Mutually exclusive with
 * the MinIO/S3 object-lock store. Does not provision infrastructure or set verifiedCoverage.
 */
@Configuration
@EnableConfigurationProperties(GcsDurableStoreProperties.class)
@ConditionalOnProperty(name = "parkio.privacy.account-erasure.durable-store.gcs.enabled", havingValue = "true")
class GcsDurableStoreConfig {

    private static final String PREFIX = "parkio.privacy.account-erasure.durable-store.gcs";

    @Bean
    ObjectLockDurableErasureRecordStore gcsDurableErasureRecordStore(GcsDurableStoreProperties properties,
                                                                     Clock clock, JdbcTemplate jdbc,
                                                                     Environment environment) {
        if ("true".equalsIgnoreCase(environment.getProperty(
                "parkio.privacy.account-erasure.durable-store.object-lock.enabled", "false"))) {
            throw new IllegalStateException(PREFIX + " and durable-store.object-lock.enabled cannot both be true");
        }
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
        Storage storage = StorageOptions.newBuilder()
                .setProjectId(properties.getProjectId())
                .build()
                .getService();
        GcsObjectRetentionBucket bucket = new GcsObjectRetentionBucket(
                new GoogleCloudStorageTransport(storage, properties.getBucket()));
        bucket.requireRetentionFeature();
        return new ObjectLockDurableErasureRecordStore(bucket, trust, signingKey.keyId(),
                EvidenceRetentionMode.parse(properties.getRetentionMode()), properties.getRetention(), clock);
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
