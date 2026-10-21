package com.parkio.auth.infrastructure.durable;

import com.google.api.client.http.javanet.DefaultConnectionFactory;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.auth.http.HttpTransportFactory;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ImpersonatedCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.NoCredentials;
import com.google.cloud.http.HttpTransportOptions;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Bucket;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.Storage.BlobTargetOption;
import com.google.cloud.storage.StorageException;
import com.google.cloud.storage.StorageOptions;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The live provider of the #483 harness: one {@link Storage} client per identity, built like
 * {@link GcsDurableStoreConfig} builds it (HTTP JSON API, default retry settings) except for its
 * credentials. Credentials come from Application Default Credentials impersonating each identity's
 * service account, so no service-account key is ever downloaded and the operator's own credentials
 * never send a storage request. Every storage HTTP request is counted for the stop thresholds.
 */
final class LiveGcsAcceptanceProvider implements GcsAcceptanceProvider {

    static final List<String> SCOPES = List.of("https://www.googleapis.com/auth/devstorage.full_control");
    private static final int TOKEN_LIFETIME_SECONDS = 3600;

    private final Map<Identity, Storage> storages;
    private final String credentials;

    private LiveGcsAcceptanceProvider(Map<Identity, Storage> storages, String credentials) {
        this.storages = Map.copyOf(storages);
        this.credentials = credentials;
    }

    /** Production use: ADC (for example {@code gcloud auth application-default login}) impersonates each account. */
    static LiveGcsAcceptanceProvider impersonating(String projectId, Map<Identity, String> serviceAccounts,
                                                   GcsAcceptanceLedger ledger) throws IOException {
        GoogleCredentials source = GoogleCredentials.getApplicationDefault();
        if (source instanceof ServiceAccountCredentials) {
            throw new IllegalArgumentException("Application Default Credentials are a service-account key file;"
                    + " use gcloud auth application-default login or workload identity instead");
        }
        HttpTransportFactory transport = countingTransport(ledger);
        Map<Identity, Storage> storages = new EnumMap<>(Identity.class);
        for (Identity identity : Identity.values()) {
            String account = Objects.requireNonNull(serviceAccounts.get(identity), "service account for " + identity);
            ImpersonatedCredentials impersonated = ImpersonatedCredentials.newBuilder()
                    .setSourceCredentials(source)
                    .setTargetPrincipal(account)
                    .setScopes(SCOPES)
                    .setLifetime(TOKEN_LIFETIME_SECONDS)
                    .build();
            storages.put(identity, StorageOptions.newBuilder()
                    .setProjectId(projectId)
                    .setCredentials(impersonated)
                    .setTransportOptions(HttpTransportOptions.newBuilder().setHttpTransportFactory(transport).build())
                    .build()
                    .getService());
        }
        return new LiveGcsAcceptanceProvider(storages,
                "ADC source " + source.getClass().getSimpleName() + " impersonating one service account per identity");
    }

    /** Request-shape tests only: every identity against a local endpoint without credentials. */
    static LiveGcsAcceptanceProvider local(String host, String projectId, GcsAcceptanceLedger ledger) {
        HttpTransportFactory transport = countingTransport(ledger);
        Map<Identity, Storage> storages = new EnumMap<>(Identity.class);
        for (Identity identity : Identity.values()) {
            storages.put(identity, StorageOptions.newBuilder()
                    .setHost(host)
                    .setProjectId(projectId)
                    .setCredentials(NoCredentials.getInstance())
                    .setTransportOptions(HttpTransportOptions.newBuilder().setHttpTransportFactory(transport).build())
                    .build()
                    .getService());
        }
        return new LiveGcsAcceptanceProvider(storages, "none (local test endpoint)");
    }

    /** One shared transport whose connection factory counts every storage request, retries included. */
    private static HttpTransportFactory countingTransport(GcsAcceptanceLedger ledger) {
        DefaultConnectionFactory connections = new DefaultConnectionFactory();
        NetHttpTransport transport = new NetHttpTransport.Builder()
                .setConnectionFactory(url -> {
                    ledger.httpRequest();
                    return connections.openConnection(url);
                })
                .build();
        return () -> transport;
    }

    @Override
    public String evidenceClass() {
        return "live-provider";
    }

    @Override
    public GcsObjectRetentionTransport transport(Identity identity, String bucket) {
        return new GoogleCloudStorageTransport(storage(identity), bucket);
    }

    @Override
    public RetentionProbe probe(Identity identity) {
        return new LiveProbe(storage(identity));
    }

    @Override
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Override
    public void sleep(Duration duration) throws InterruptedException {
        Thread.sleep(duration.toMillis());
    }

    @Override
    public Map<String, Object> describe() {
        Map<String, Object> description = new LinkedHashMap<>();
        description.put("api", "Cloud Storage JSON API over HTTP (google-cloud-storage, default retry settings)");
        description.put("library", storage(Identity.READER).getOptions().getLibraryVersion());
        description.put("credentials", credentials);
        description.put("scopes", SCOPES);
        return description;
    }

    private Storage storage(Identity identity) {
        return Objects.requireNonNull(storages.get(identity), "no client for " + identity);
    }

    private static final class LiveProbe implements RetentionProbe {

        private final Storage storage;

        LiveProbe(Storage storage) {
            this.storage = storage;
        }

        @Override
        public BucketSnapshot bucket(String bucket) {
            Bucket found = storage.get(bucket, Storage.BucketGetOption.fields(
                    Storage.BucketField.NAME, Storage.BucketField.PROJECT, Storage.BucketField.LOCATION,
                    Storage.BucketField.LOCATION_TYPE, Storage.BucketField.STORAGE_CLASS,
                    Storage.BucketField.VERSIONING, Storage.BucketField.OBJECT_RETENTION,
                    Storage.BucketField.RETENTION_POLICY, Storage.BucketField.SOFT_DELETE_POLICY,
                    Storage.BucketField.IAMCONFIGURATION, Storage.BucketField.HIERARCHICAL_NAMESPACE,
                    Storage.BucketField.LABELS, Storage.BucketField.TIME_CREATED, Storage.BucketField.METAGENERATION));
            if (found == null) {
                throw new StorageException(404, "bucket not found: " + bucket);
            }
            BucketInfo.ObjectRetention retention = found.getObjectRetention();
            String retentionMode = retention == null || retention.getMode() == null ? null
                    : retention.getMode() == BucketInfo.ObjectRetention.Mode.ENABLED ? "Enabled" : retention.getMode().toString();
            BucketInfo.IamConfiguration iam = found.getIamConfiguration();
            BucketInfo.SoftDeletePolicy softDelete = found.getSoftDeletePolicy();
            BucketInfo.HierarchicalNamespace namespace = found.getHierarchicalNamespace();
            return new BucketSnapshot(found.getName(),
                    found.getProject() == null ? null : found.getProject().toString(),
                    found.getLocation(), found.getLocationType(),
                    found.getStorageClass() == null ? null : found.getStorageClass().name(),
                    found.versioningEnabled(), retentionMode,
                    found.getRetentionPeriodDuration() == null ? null : found.getRetentionPeriodDuration().toSeconds(),
                    softDelete == null || softDelete.getRetentionDuration() == null ? null
                            : softDelete.getRetentionDuration().toSeconds(),
                    iam == null ? null : iam.isUniformBucketLevelAccessEnabled(),
                    iam == null || iam.getPublicAccessPrevention() == null ? null
                            : iam.getPublicAccessPrevention().name().toLowerCase(Locale.ROOT),
                    namespace == null ? null : namespace.getEnabled(),
                    found.getLabels(),
                    found.getCreateTimeOffsetDateTime() == null ? null : found.getCreateTimeOffsetDateTime().toString(),
                    found.getMetageneration());
        }

        @Override
        public GenerationState state(String bucket, String name, long generation) {
            Blob blob = storage.get(BlobId.of(bucket, name, generation), Storage.BlobGetOption.fields(
                    Storage.BlobField.NAME, Storage.BlobField.GENERATION, Storage.BlobField.RETENTION));
            if (blob == null) {
                return GenerationState.absent();
            }
            BlobInfo.Retention retention = blob.getRetention();
            if (retention == null || retention.getMode() == null || retention.getRetainUntilTime() == null) {
                return new GenerationState(true, null, null);
            }
            return new GenerationState(true, retention.getMode() == BlobInfo.Retention.Mode.LOCKED
                    ? EvidenceRetentionMode.COMPLIANCE : EvidenceRetentionMode.GOVERNANCE,
                    retention.getRetainUntilTime().toInstant());
        }

        @Override
        public ProbeOutcome setRetention(String bucket, String name, long liveGeneration, EvidenceRetentionMode mode,
                                         Instant retainUntil, boolean overrideUnlockedRetention) {
            BlobInfo.Retention retention = BlobInfo.Retention.newBuilder()
                    .setMode(mode == EvidenceRetentionMode.COMPLIANCE
                            ? BlobInfo.Retention.Mode.LOCKED : BlobInfo.Retention.Mode.UNLOCKED)
                    .setRetainUntilTime(OffsetDateTime.ofInstant(retainUntil, ZoneOffset.UTC))
                    .build();
            return update(BlobInfo.newBuilder(BlobId.of(bucket, name)).setRetention(retention).build(),
                    liveGeneration, overrideUnlockedRetention);
        }

        @Override
        public ProbeOutcome clearRetention(String bucket, String name, long liveGeneration,
                                           boolean overrideUnlockedRetention) {
            return update(BlobInfo.newBuilder(BlobId.of(bucket, name)).setRetention(null).build(), liveGeneration,
                    overrideUnlockedRetention);
        }

        @Override
        public ProbeOutcome delete(String bucket, String name, long generation) {
            try {
                return storage.delete(BlobId.of(bucket, name, generation))
                        ? ProbeOutcome.ok()
                        : new ProbeOutcome(false, 404, "notFound", "no such generation");
            } catch (StorageException ex) {
                return refused(ex);
            }
        }

        /** PATCH of the live object, guarded by {@code ifGenerationMatch} so it can only hit that generation. */
        private ProbeOutcome update(BlobInfo info, long liveGeneration, boolean overrideUnlockedRetention) {
            List<BlobTargetOption> options = new ArrayList<>();
            options.add(BlobTargetOption.generationMatch(liveGeneration));
            if (overrideUnlockedRetention) {
                options.add(BlobTargetOption.overrideUnlockedRetention(true));
            }
            try {
                storage.update(info, options.toArray(BlobTargetOption[]::new));
                return ProbeOutcome.ok();
            } catch (StorageException ex) {
                return refused(ex);
            }
        }

        private static ProbeOutcome refused(StorageException ex) {
            return new ProbeOutcome(false, ex.getCode(), GcsAcceptanceLedger.sanitize(ex.getReason()),
                    GcsAcceptanceLedger.sanitize(ex.getMessage()));
        }
    }
}
