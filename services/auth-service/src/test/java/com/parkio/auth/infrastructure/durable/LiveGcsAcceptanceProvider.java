package com.parkio.auth.infrastructure.durable;

import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.javanet.DefaultConnectionFactory;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.Data;
import com.google.api.client.util.DateTime;
import com.google.api.services.storage.model.StorageObject;
import com.google.auth.Credentials;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.http.HttpTransportFactory;
import com.google.auth.oauth2.ComputeEngineCredentials;
import com.google.auth.oauth2.ExternalAccountAuthorizedUserCredentials;
import com.google.auth.oauth2.ExternalAccountCredentials;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ImpersonatedCredentials;
import com.google.auth.oauth2.UserCredentials;
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
 * {@link GcsDurableStoreConfig} builds it (HTTP JSON API, default retry settings: 6 attempts within
 * 50 s; library default timeouts: 20 s connect and 20 s read per request) except for its
 * credentials. Credentials come from keyless Application Default Credentials (a user login,
 * workload identity federation or the metadata server) impersonating each identity's service
 * account, so no service-account key is used and the operator's own credentials never send a
 * storage request. Every HTTP connection, storage and token requests alike, passes the ledger's
 * gate first: after a stop, at the request cap or past the wall-clock cap nothing more is sent.
 */
final class LiveGcsAcceptanceProvider implements GcsAcceptanceProvider {

    static final List<String> SCOPES = List.of("https://www.googleapis.com/auth/devstorage.full_control");
    private static final int TOKEN_LIFETIME_SECONDS = 3600;

    private final Map<Identity, Storage> storages;
    private final Map<Identity, com.google.api.services.storage.Storage> jsonApis;
    private final String credentials;
    private final String evidenceClass;
    private final String endpoint;

    private LiveGcsAcceptanceProvider(Map<Identity, Storage> storages,
                                      Map<Identity, com.google.api.services.storage.Storage> jsonApis,
                                      String credentials, String evidenceClass, String endpoint) {
        this.storages = Map.copyOf(storages);
        this.jsonApis = Map.copyOf(jsonApis);
        this.credentials = credentials;
        this.evidenceClass = evidenceClass;
        this.endpoint = endpoint;
    }

    /** Production use: ADC (for example {@code gcloud auth application-default login}) impersonates each account. */
    static LiveGcsAcceptanceProvider impersonating(String projectId, Map<Identity, String> serviceAccounts,
                                                   GcsAcceptanceLedger ledger) throws IOException {
        HttpTransportFactory tokens = gatedTransport(ledger, true);
        GoogleCredentials source = requireKeyless(GoogleCredentials.getApplicationDefault(tokens));
        HttpTransportFactory transport = gatedTransport(ledger, false);
        Map<Identity, Storage> storages = new EnumMap<>(Identity.class);
        Map<Identity, com.google.api.services.storage.Storage> jsonApis = new EnumMap<>(Identity.class);
        for (Identity identity : Identity.values()) {
            String account = Objects.requireNonNull(serviceAccounts.get(identity), "service account for " + identity);
            ImpersonatedCredentials impersonated = ImpersonatedCredentials.newBuilder()
                    .setSourceCredentials(source)
                    .setTargetPrincipal(account)
                    .setScopes(SCOPES)
                    .setLifetime(TOKEN_LIFETIME_SECONDS)
                    .setHttpTransportFactory(tokens)
                    .build();
            storages.put(identity, StorageOptions.newBuilder()
                    .setProjectId(projectId)
                    .setCredentials(impersonated)
                    .setTransportOptions(HttpTransportOptions.newBuilder().setHttpTransportFactory(transport).build())
                    .build()
                    .getService());
            jsonApis.put(identity, jsonApi(transport, null, impersonated));
        }
        // The endpoint comes from a client built above: default options would resolve a default project
        // over the network outside the gate. ADC is loaded once, above, through the gated transport.
        return new LiveGcsAcceptanceProvider(storages, jsonApis,
                "ADC source " + source.getClass().getSimpleName() + " impersonating one service account per identity",
                "live-provider", storages.get(Identity.READER).getOptions().getHost());
    }

    /**
     * Only keyless sources: a user login ({@code gcloud auth application-default login}), workload
     * identity federation or the metadata server. Anything else, a service-account key file above
     * all (also as the source of an impersonation file), is refused before any request.
     */
    static GoogleCredentials requireKeyless(GoogleCredentials source) {
        if (source instanceof UserCredentials || source instanceof ExternalAccountCredentials
                || source instanceof ExternalAccountAuthorizedUserCredentials
                || source instanceof ComputeEngineCredentials) {
            return source;
        }
        throw new IllegalArgumentException("Application Default Credentials of type "
                + source.getClass().getSimpleName() + " are refused: use gcloud auth application-default login"
                + " or workload identity, never a service-account key");
    }

    /** Request-shape tests only: every identity against a local endpoint without credentials. */
    static LiveGcsAcceptanceProvider local(String host, String projectId, GcsAcceptanceLedger ledger) {
        HttpTransportFactory transport = gatedTransport(ledger, false);
        Map<Identity, Storage> storages = new EnumMap<>(Identity.class);
        Map<Identity, com.google.api.services.storage.Storage> jsonApis = new EnumMap<>(Identity.class);
        for (Identity identity : Identity.values()) {
            storages.put(identity, StorageOptions.newBuilder()
                    .setHost(host)
                    .setProjectId(projectId)
                    .setCredentials(NoCredentials.getInstance())
                    .setTransportOptions(HttpTransportOptions.newBuilder().setHttpTransportFactory(transport).build())
                    .build()
                    .getService());
            jsonApis.put(identity, jsonApi(transport, host, null));
        }
        return new LiveGcsAcceptanceProvider(storages, jsonApis, "none (local test endpoint)", "local-test-endpoint",
                host);
    }

    /**
     * The JSON API client the storage library itself wraps, for the generation-addressed PATCH the
     * library cannot send. Same gated transport and credentials; no retries (a PATCH is not idempotent).
     */
    private static com.google.api.services.storage.Storage jsonApi(HttpTransportFactory transport, String host,
                                                                   Credentials credentials) {
        HttpRequestInitializer initializer = credentials == null ? request -> { }
                : new HttpCredentialsAdapter(credentials);
        com.google.api.services.storage.Storage.Builder builder = new com.google.api.services.storage.Storage.Builder(
                transport.create(), GsonFactory.getDefaultInstance(), initializer)
                .setApplicationName("parkio-n02-gcs-acceptance");
        if (host != null) {
            builder.setRootUrl(host.endsWith("/") ? host : host + "/");
        }
        return builder.build();
    }

    /** One shared transport whose connection factory passes every request, retries included, through the gate. */
    static HttpTransportFactory gatedTransport(GcsAcceptanceLedger ledger, boolean tokenRequests) {
        DefaultConnectionFactory connections = new DefaultConnectionFactory();
        NetHttpTransport transport = new NetHttpTransport.Builder()
                .setConnectionFactory(url -> {
                    ledger.beforeHttpRequest(tokenRequests);
                    return connections.openConnection(url);
                })
                .build();
        return () -> transport;
    }

    @Override
    public String evidenceClass() {
        return evidenceClass;
    }

    @Override
    public GcsObjectRetentionTransport transport(Identity identity, String bucket) {
        return new GoogleCloudStorageTransport(storage(identity), bucket);
    }

    @Override
    public RetentionProbe probe(Identity identity) {
        return new LiveProbe(storage(identity), Objects.requireNonNull(jsonApis.get(identity), "no JSON API client"));
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
        description.put("api", "Cloud Storage JSON API over HTTP (google-cloud-storage, default retry settings:"
                + " 6 attempts within 50 s; default timeouts: 20 s connect, 20 s read)");
        description.put("library", storage(Identity.READER).getOptions().getLibraryVersion());
        description.put("libraries", Map.of(
                "google-auth-library-oauth2-http", mavenVersion("com.google.auth", "google-auth-library-oauth2-http"),
                "google-http-client", mavenVersion("com.google.http-client", "google-http-client"),
                "gax", mavenVersion("com.google.api", "gax"),
                "google-cloud-core-http", mavenVersion("com.google.cloud", "google-cloud-core-http")));
        description.put("endpoint", endpoint);
        description.put("credentials", credentials);
        description.put("scopes", SCOPES);
        return description;
    }

    /** The version a library jar declares in its Maven metadata, or "unknown". */
    static String mavenVersion(String group, String artifact) {
        String resource = "META-INF/maven/" + group + "/" + artifact + "/pom.properties";
        try (var in = LiveGcsAcceptanceProvider.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                return "unknown";
            }
            java.util.Properties properties = new java.util.Properties();
            properties.load(in);
            return properties.getProperty("version", "unknown");
        } catch (IOException ex) {
            return "unknown";
        }
    }

    private Storage storage(Identity identity) {
        return Objects.requireNonNull(storages.get(identity), "no client for " + identity);
    }

    private static final class LiveProbe implements RetentionProbe {

        private final Storage storage;
        private final com.google.api.services.storage.Storage jsonApi;

        LiveProbe(Storage storage, com.google.api.services.storage.Storage jsonApi) {
            this.storage = storage;
            this.jsonApi = jsonApi;
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
        public ProbeOutcome setRetentionOfGeneration(String bucket, String name, long generation,
                                                     EvidenceRetentionMode mode, Instant retainUntil,
                                                     boolean overrideUnlockedRetention) {
            StorageObject content = new StorageObject().setRetention(new StorageObject.Retention()
                    .setMode(mode == EvidenceRetentionMode.COMPLIANCE ? "Locked" : "Unlocked")
                    .setRetainUntilTime(new DateTime(false, retainUntil.toEpochMilli(), 0)));
            return patchGeneration(bucket, name, generation, content, overrideUnlockedRetention);
        }

        @Override
        public ProbeOutcome clearRetentionOfGeneration(String bucket, String name, long generation,
                                                       boolean overrideUnlockedRetention) {
            StorageObject content = new StorageObject().setRetention(Data.nullOf(StorageObject.Retention.class));
            return patchGeneration(bucket, name, generation, content, overrideUnlockedRetention);
        }

        @Override
        public ProbeOutcome insertWithoutRetention(String bucket, String name, byte[] bytes) {
            try {
                storage.create(BlobInfo.newBuilder(BlobId.of(bucket, name)).setContentType("application/json").build(),
                        bytes);
                return ProbeOutcome.ok();
            } catch (StorageException ex) {
                return refused(ex);
            }
        }

        /** {@code objects.patch} with the {@code generation} parameter, so it addresses that generation only. */
        private ProbeOutcome patchGeneration(String bucket, String name, long generation, StorageObject content,
                                            boolean overrideUnlockedRetention) {
            try {
                com.google.api.services.storage.Storage.Objects.Patch patch = jsonApi.objects()
                        .patch(bucket, name, content)
                        .setGeneration(generation);
                if (overrideUnlockedRetention) {
                    patch.setOverrideUnlockedRetention(true);
                }
                patch.execute();
                return ProbeOutcome.ok();
            } catch (GoogleJsonResponseException ex) {
                GoogleJsonError details = ex.getDetails();
                String reason = details == null || details.getErrors() == null || details.getErrors().isEmpty()
                        ? "" : String.valueOf(details.getErrors().get(0).getReason());
                String message = details == null ? ex.getStatusMessage() : details.getMessage();
                return new ProbeOutcome(false, ex.getStatusCode(), GcsAcceptanceLedger.sanitize(reason),
                        GcsAcceptanceLedger.sanitize(message));
            } catch (IOException ex) {
                return new ProbeOutcome(false, 0, "io",
                        GcsAcceptanceLedger.sanitize(ex.getClass().getSimpleName() + ": " + ex.getMessage()));
            }
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
