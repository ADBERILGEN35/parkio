package com.parkio.auth.infrastructure.durable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.http.EmptyContent;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpRequest;
import com.google.auth.http.HttpTransportFactory;
import com.google.auth.oauth2.GoogleCredentials;
import com.parkio.auth.infrastructure.durable.GcsAcceptanceLedger.Caps;
import com.parkio.auth.infrastructure.durable.GcsAcceptanceProvider.Identity;
import com.parkio.auth.infrastructure.durable.GcsAcceptanceProvider.ProbeOutcome;
import com.parkio.auth.infrastructure.durable.GcsAcceptanceProvider.RetentionProbe;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What the real Cloud Storage client sends for the merged adapter's transport and for the
 * harness's probes, recorded by a local HTTP endpoint before any paid run: discovery asks for
 * Object Retention and Versioning, uploads carry the retention, a throttled upload is resent by the
 * adapter and not by the client, reads and deletes address one generation, retention changes patch
 * the live object under {@code ifGenerationMatch} or address one generation with the JSON API's
 * {@code generation} parameter, a refusal keeps the provider's status and reason, the stop gate
 * refuses requests before they are sent, and only keyless credentials are accepted. Local
 * request-shape evidence only, not #483.
 */
class LiveGcsAcceptanceProviderRequestShapeTest {

    private static final String BUCKET = "parkio-n02-orl-accept-20261022-4c1e9b";
    private static final String NAME = "runs/20261022T090000Z-0001/adapter/compliance-basic.json";
    private static final Instant UNTIL = Instant.parse("2026-10-22T11:00:00Z");

    private record Recorded(String method, String path, Map<String, String> query, String body) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private final Queue<Integer> uploadStatuses = new ConcurrentLinkedQueue<>();
    private HttpServer server;
    private GcsAcceptanceLedger ledger;
    private LiveGcsAcceptanceProvider provider;
    private volatile boolean versioning = true;
    private volatile int deleteStatus = 204;
    private volatile int patchStatus = 200;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        ledger = new GcsAcceptanceLedger(Clock.systemUTC(), Caps.APPROVED);
        provider = LiveGcsAcceptanceProvider.local("http://127.0.0.1:" + server.getAddress().getPort(),
                "parkio-n02-accept-4c1e9b", ledger);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void discoveryAsksForRetentionAndVersioningAndRefusesAnUnversionedBucket() {
        GcsObjectRetentionTransport transport = provider.transport(Identity.WRITER, BUCKET);

        transport.requireObjectRetentionEnabled();
        versioning = false;

        assertThatThrownBy(transport::requireObjectRetentionEnabled)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Object Versioning");
        Recorded first = requests.get(0);
        assertThat(first.method()).isEqualTo("GET");
        assertThat(first.path()).isEqualTo("/storage/v1/b/" + BUCKET);
        assertThat(first.query().get("fields")).contains("objectRetention").contains("versioning");
    }

    @Test
    void uploadCarriesTheRetentionWithoutAPreconditionAndReturnsTheGeneration() {
        GcsObjectRetentionTransport transport = provider.transport(Identity.WRITER, BUCKET);

        long locked = transport.createGeneration(NAME, "{\"a\":1}".getBytes(StandardCharsets.UTF_8),
                EvidenceRetentionMode.COMPLIANCE, UNTIL);
        transport.createGeneration(NAME, "{\"a\":2}".getBytes(StandardCharsets.UTF_8),
                EvidenceRetentionMode.GOVERNANCE, UNTIL);

        assertThat(locked).isEqualTo(1761123456000001L);
        Recorded upload = requests.get(0);
        assertThat(upload.method()).isEqualTo("POST");
        assertThat(upload.path()).isEqualTo("/upload/storage/v1/b/" + BUCKET + "/o");
        assertThat(upload.query()).containsEntry("uploadType", "multipart").doesNotContainKey("ifGenerationMatch");
        assertThat(upload.body()).contains("\"retention\"").contains("\"mode\":\"Locked\"")
                .contains("2026-10-22T11:00:00").contains(NAME);
        assertThat(requests.get(1).body()).contains("\"mode\":\"Unlocked\"");
    }

    @Test
    void listingAndReadsAddressGenerations() {
        GcsObjectRetentionTransport transport = provider.transport(Identity.READER, BUCKET);

        GcsObjectRetentionTransport.ListPage page = transport.listGenerations(NAME, Optional.of("tok-1"), 100);
        byte[] bytes = transport.readGeneration(NAME, 5);
        GcsObjectRetentionTransport.RetentionState retention = transport.retention(NAME, 5);

        assertThat(page.items()).extracting(GcsObjectRetentionTransport.ListedGeneration::generation).containsExactly(5L, 3L);
        assertThat(page.nextPageToken()).contains("tok-2");
        assertThat(requests.get(0).query()).containsEntry("prefix", NAME).containsEntry("versions", "true")
                .containsEntry("maxResults", "100").containsEntry("pageToken", "tok-1");
        assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo("generation-5");
        assertThat(requests.get(1).query()).containsEntry("generation", "5").containsEntry("alt", "media");
        assertThat(retention.mode()).isEqualTo(EvidenceRetentionMode.COMPLIANCE);
        assertThat(retention.retainUntil()).isEqualTo(UNTIL);
        assertThat(requests.get(2).query()).containsEntry("generation", "5").doesNotContainKey("alt");
    }

    @Test
    void retentionChangesPatchOnlyTheLiveObjectUnderAGenerationPrecondition() {
        RetentionProbe probe = provider.probe(Identity.PROBE_ADMIN);

        assertThat(probe.setRetention(BUCKET, NAME, 7, EvidenceRetentionMode.COMPLIANCE, UNTIL, true).accepted()).isTrue();
        assertThat(probe.clearRetention(BUCKET, NAME, 7, true).accepted()).isTrue();
        assertThat(probe.setRetention(BUCKET, NAME, 7, EvidenceRetentionMode.GOVERNANCE, UNTIL, false).accepted()).isTrue();

        Recorded shorten = requests.get(0);
        assertThat(shorten.method()).isEqualTo("PATCH");
        assertThat(shorten.path()).isEqualTo("/storage/v1/b/" + BUCKET + "/o/" + NAME);
        assertThat(shorten.query()).containsEntry("ifGenerationMatch", "7")
                .containsEntry("overrideUnlockedRetention", "true").doesNotContainKey("generation");
        assertThat(shorten.body()).contains("\"mode\":\"Locked\"").contains("2026-10-22T11:00:00");
        assertThat(requests.get(1).body()).contains("\"retention\":null");
        assertThat(requests.get(2).query()).doesNotContainKey("overrideUnlockedRetention");
        assertThat(requests.get(2).body()).contains("\"mode\":\"Unlocked\"");
    }

    @Test
    void deleteAddressesOneGenerationAndARefusalKeepsTheProviderStatusAndReason() {
        RetentionProbe probe = provider.probe(Identity.PROBE_ADMIN);
        deleteStatus = 403;
        patchStatus = 403;

        ProbeOutcome refused = probe.delete(BUCKET, NAME, 9);
        ProbeOutcome refusedPatch = probe.clearRetention(BUCKET, NAME, 9, true);
        deleteStatus = 204;
        ProbeOutcome deleted = probe.delete(BUCKET, NAME, 9);

        assertThat(refused.accepted()).isFalse();
        assertThat(refused.code()).isEqualTo(403);
        assertThat(refused.reason()).isEqualTo("forbidden");
        assertThat(refused.message()).contains("retention");
        assertThat(refusedPatch.code()).isEqualTo(403);
        assertThat(deleted.accepted()).isTrue();
        assertThat(requests.get(0).method()).isEqualTo("DELETE");
        assertThat(requests.get(0).path()).isEqualTo("/storage/v1/b/" + BUCKET + "/o/" + NAME);
        assertThat(requests.get(0).query()).containsEntry("generation", "9");
        assertThat(requests).hasSize(3);
    }

    @Test
    void everyStorageRequestIsCountedForTheStopThreshold() {
        GcsObjectRetentionTransport transport = provider.transport(Identity.READER, BUCKET);

        transport.requireObjectRetentionEnabled();
        transport.listGenerations(NAME, Optional.empty(), 100);
        provider.probe(Identity.READER).state(BUCKET, NAME, 5);

        assertThat(ledger.httpRequests()).isEqualTo(requests.size()).isEqualTo(3);
    }

    @Test
    void aThrottledUploadIsResentByTheAdapterAndNeverByTheClient() {
        uploadStatuses.add(429);
        List<Duration> pauses = new ArrayList<>();
        GcsObjectRetentionBucket bucket = new GcsObjectRetentionBucket(provider.transport(Identity.WRITER, BUCKET),
                pauses::add);

        String generation = bucket.put(NAME, "{\"a\":1}".getBytes(StandardCharsets.UTF_8),
                EvidenceRetentionMode.COMPLIANCE, UNTIL.atZone(ZoneOffset.UTC));

        assertThat(requests).hasSize(2)
                .allMatch(request -> request.method().equals("POST") && request.path().startsWith("/upload/"));
        assertThat(pauses).containsExactly(Duration.ofMillis(1_100));
        assertThat(generation).isEqualTo("1761123456000002");
    }

    @Test
    void aGenerationAddressedPatchSelectsThatGenerationOnly() {
        RetentionProbe probe = provider.probe(Identity.PROBE_ADMIN);

        assertThat(probe.setRetentionOfGeneration(BUCKET, NAME, 3, EvidenceRetentionMode.COMPLIANCE, UNTIL, true)
                .accepted()).isTrue();
        assertThat(probe.clearRetentionOfGeneration(BUCKET, NAME, 3, true).accepted()).isTrue();
        patchStatus = 403;
        ProbeOutcome refused = probe.setRetentionOfGeneration(BUCKET, NAME, 3, EvidenceRetentionMode.COMPLIANCE,
                UNTIL, true);

        Recorded shorten = requests.get(0);
        assertThat(shorten.method()).isEqualTo("PATCH");
        assertThat(shorten.path()).isEqualTo("/storage/v1/b/" + BUCKET + "/o/" + NAME);
        assertThat(shorten.query()).containsEntry("generation", "3").containsEntry("overrideUnlockedRetention", "true")
                .doesNotContainKey("ifGenerationMatch");
        assertThat(shorten.body()).contains("\"mode\":\"Locked\"").contains("2026-10-22T11:00:00");
        assertThat(requests.get(1).body()).contains("\"retention\":null");
        assertThat(refused.accepted()).isFalse();
        assertThat(refused.code()).isEqualTo(403);
        assertThat(refused.reason()).isEqualTo("forbidden");
        assertThat(requests).hasSize(3);
    }

    @Test
    void anInsertWithoutRetentionCarriesNone() {
        ProbeOutcome outcome = provider.probe(Identity.READER).insertWithoutRetention(BUCKET, NAME,
                "{}".getBytes(StandardCharsets.UTF_8));

        assertThat(outcome.accepted()).isTrue();
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).path()).isEqualTo("/upload/storage/v1/b/" + BUCKET + "/o");
        assertThat(requests.get(0).body()).contains(NAME).doesNotContain("retention");
    }

    @Test
    void theGateRefusesRequestsPastTheCapsBeforeAnythingIsSent() throws Exception {
        String host = "http://127.0.0.1:" + server.getAddress().getPort();
        GcsAcceptanceLedger capped = new GcsAcceptanceLedger(Clock.systemUTC(),
                new Caps(1_200, 2, 320, 40, Duration.ofMinutes(45)));
        GcsObjectRetentionTransport transport = LiveGcsAcceptanceProvider.local(host, "parkio-n02-accept-4c1e9b", capped)
                .transport(Identity.READER, BUCKET);

        transport.requireObjectRetentionEnabled();
        transport.listGenerations(NAME, Optional.empty(), 100);
        assertThatThrownBy(() -> transport.readGeneration(NAME, 5))
                .satisfies(ex -> assertThat(GcsAcceptanceLedger.describe("objects.get.media", ex).reason())
                        .isEqualTo("stopped"));
        assertThat(requests).hasSize(2);
        assertThat(capped.abortReason()).contains("HTTP request cap 2");

        GcsAcceptanceLedger late = new GcsAcceptanceLedger(Clock.systemUTC(),
                new Caps(1_200, 1_500, 320, 40, Duration.ofMillis(1)));
        Thread.sleep(20);
        GcsObjectRetentionTransport expired = LiveGcsAcceptanceProvider.local(host, "parkio-n02-accept-4c1e9b", late)
                .transport(Identity.READER, BUCKET);
        assertThatThrownBy(expired::requireObjectRetentionEnabled)
                .satisfies(ex -> assertThat(GcsAcceptanceLedger.describe("buckets.get", ex).reason()).isEqualTo("stopped"));
        assertThat(requests).hasSize(2);
        assertThat(late.abortReason()).contains("wall-clock cap");
    }

    @Test
    void tokenRequestsPassTheSameGateAndCountTowardsTheRequestCap() throws Exception {
        GenericUrl token = new GenericUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/token");
        GcsAcceptanceLedger capped = new GcsAcceptanceLedger(Clock.systemUTC(),
                new Caps(1_200, 2, 320, 40, Duration.ofMinutes(45)));
        HttpTransportFactory tokens = LiveGcsAcceptanceProvider.gatedTransport(capped, true);

        for (int i = 0; i < 2; i++) {
            HttpRequest request = tokens.create().createRequestFactory().buildPostRequest(token, new EmptyContent());
            request.setThrowExceptionOnExecuteError(false);
            request.execute().disconnect();
        }
        assertThatThrownBy(() -> tokens.create().createRequestFactory().buildPostRequest(token, new EmptyContent()).execute())
                .satisfies(ex -> assertThat(GcsAcceptanceLedger.describe("token", ex).reason()).isEqualTo("stopped"));

        assertThat(requests).hasSize(2);
        assertThat(capped.counters()).containsEntry("tokenHttp", 2L).containsEntry("http", 0L);
        assertThat(capped.abortReason()).contains("HTTP request cap 2");
    }

    @Test
    void onlyKeylessApplicationDefaultCredentialsAreAccepted() throws Exception {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("type", "authorized_user");
        user.put("client_id", "n02-test.apps.googleusercontent.com");
        user.put("client_secret", "n02-test-client");
        user.put("refresh_token", "n02-test-refresh");
        Map<String, Object> workloadIdentity = new LinkedHashMap<>();
        workloadIdentity.put("type", "external" + "_account");
        workloadIdentity.put("audience",
                "//iam.googleapis.com/projects/1/locations/global/workloadIdentityPools/pool/providers/provider");
        workloadIdentity.put("subject_token_type", "urn:ietf:params:oauth:token-type:jwt");
        workloadIdentity.put("token_url", "https://sts.googleapis.com/v1/token");
        workloadIdentity.put("credential_source", Map.of("file", "/tmp/n02-test-subject-token"));
        Map<String, Object> keyFile = serviceAccountKeyFile();
        Map<String, Object> impersonatingKeyFile = new LinkedHashMap<>();
        impersonatingKeyFile.put("type", "impersonated_service" + "_account");
        impersonatingKeyFile.put("service_account_impersonation_url", "https://iamcredentials.googleapis.com/v1/"
                + "projects/-/serviceAccounts/n02-writer@parkio-n02-accept-4c1e9b.iam.gserviceaccount.com:generateAccessToken");
        impersonatingKeyFile.put("source_credentials", keyFile);

        assertThat(LiveGcsAcceptanceProvider.requireKeyless(credentials(user))).isNotNull();
        assertThat(LiveGcsAcceptanceProvider.requireKeyless(credentials(workloadIdentity))).isNotNull();
        assertThatThrownBy(() -> LiveGcsAcceptanceProvider.requireKeyless(credentials(keyFile)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ServiceAccountCredentials");
        assertThatThrownBy(() -> LiveGcsAcceptanceProvider.requireKeyless(credentials(impersonatingKeyFile)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ImpersonatedCredentials");
    }

    @Test
    void theLocalEndpointIsNeverLabelledAsTheLiveProvider() {
        assertThat(provider.evidenceClass()).isEqualTo("local-test-endpoint");
        assertThat(provider.describe().get("endpoint")).isEqualTo("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @SuppressWarnings("deprecation")
    private static GoogleCredentials credentials(Map<String, Object> json) throws IOException {
        return GoogleCredentials.fromStream(new ByteArrayInputStream(JSON.writeValueAsBytes(json)));
    }

    /** A service-account key file with a key generated for this test only. */
    private static Map<String, Object> serviceAccountKeyFile() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String marker = "PRIVATE" + " KEY";
        String pem = "-----BEGIN " + marker + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                        .encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
                + "\n-----END " + marker + "-----\n";
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("type", "service" + "_account");
        json.put("project_id", "parkio-n02-accept-4c1e9b");
        json.put("private_key_id", "n02-test-key");
        json.put("private_key", pem);
        json.put("client_email", "n02-writer@parkio-n02-accept-4c1e9b.iam.gserviceaccount.com");
        json.put("client_id", "100000000000000000001");
        json.put("token_uri", "https://oauth2.googleapis.com/token");
        return json;
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(read(exchange), StandardCharsets.UTF_8);
        Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
        String path = exchange.getRequestURI().getPath();
        // HttpURLConnection cannot send PATCH; the client sends POST with X-HTTP-Method-Override.
        String override = exchange.getRequestHeaders().getFirst("X-HTTP-Method-Override");
        String method = override == null ? exchange.getRequestMethod() : override;
        requests.add(new Recorded(method, path, query, body));
        if (method.equals("GET") && path.equals("/storage/v1/b/" + BUCKET)) {
            respond(exchange, 200, "{\"name\":\"" + BUCKET + "\",\"objectRetention\":{\"mode\":\"Enabled\"}"
                    + (versioning ? ",\"versioning\":{\"enabled\":true}" : "") + "}");
        } else if (method.equals("POST") && path.startsWith("/upload/")) {
            Integer status = uploadStatuses.poll();
            if (status != null) {
                respond(exchange, status, error(status));
                return;
            }
            long generation = 1761123456000000L + requests.stream().filter(r -> r.method().equals("POST")).count();
            respond(exchange, 200, object(generation));
        } else if (method.equals("GET") && path.equals("/storage/v1/b/" + BUCKET + "/o")) {
            respond(exchange, 200, "{\"kind\":\"storage#objects\",\"items\":[" + object(5) + "," + object(3)
                    + "],\"nextPageToken\":\"tok-2\"}");
        } else if (method.equals("GET") && "media".equals(query.get("alt"))) {
            exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
            byte[] media = ("generation-" + query.get("generation")).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, media.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(media);
            }
        } else if (method.equals("GET")) {
            respond(exchange, 200, object(Long.parseLong(query.getOrDefault("generation", "5"))));
        } else if (method.equals("PATCH")) {
            if (patchStatus == 200) {
                respond(exchange, 200, object(7));
            } else {
                respond(exchange, patchStatus, error(patchStatus));
            }
        } else if (method.equals("DELETE")) {
            if (deleteStatus == 204) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
            } else {
                respond(exchange, deleteStatus, error(deleteStatus));
            }
        } else {
            respond(exchange, 400, error(400));
        }
    }

    private static String object(long generation) {
        return "{\"kind\":\"storage#object\",\"bucket\":\"" + BUCKET + "\",\"name\":\"" + NAME + "\",\"generation\":\""
                + generation + "\",\"metageneration\":\"1\",\"retention\":{\"mode\":\"Locked\",\"retainUntilTime\":\""
                + UNTIL + "\"}}";
    }

    private static String error(int code) {
        return "{\"error\":{\"code\":" + code + ",\"message\":\"Object is subject to object retention and cannot be"
                + " deleted, overwritten or archived\",\"errors\":[{\"reason\":\"forbidden\",\"message\":\"retention\"}]}}";
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static byte[] read(HttpExchange exchange) throws IOException {
        byte[] raw;
        try (InputStream in = exchange.getRequestBody()) {
            raw = in.readAllBytes();
        }
        if ("gzip".equalsIgnoreCase(exchange.getRequestHeaders().getFirst("Content-Encoding")) && raw.length > 0) {
            try (InputStream unzipped = new GZIPInputStream(new ByteArrayInputStream(raw))) {
                return unzipped.readAllBytes();
            }
        }
        return raw;
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> query = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return query;
        }
        for (String pair : raw.split("&")) {
            int split = pair.indexOf('=');
            String key = URLDecoder.decode(split < 0 ? pair : pair.substring(0, split), StandardCharsets.UTF_8);
            String value = split < 0 ? "" : URLDecoder.decode(pair.substring(split + 1), StandardCharsets.UTF_8);
            query.put(key, value);
        }
        return query;
    }
}
