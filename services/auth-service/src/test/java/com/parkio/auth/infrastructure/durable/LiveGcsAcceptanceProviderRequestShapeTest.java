package com.parkio.auth.infrastructure.durable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What the real Cloud Storage client sends for the merged adapter's transport and for the
 * harness's probes, recorded by a local HTTP endpoint before any paid run: discovery asks for
 * Object Retention and Versioning, uploads carry the retention, reads and deletes address one
 * generation, retention changes patch only the live object under {@code ifGenerationMatch}, and a
 * refusal keeps the provider's status and reason. Local request-shape evidence only, not #483.
 */
class LiveGcsAcceptanceProviderRequestShapeTest {

    private static final String BUCKET = "parkio-n02-orl-accept-20261022-4c1e9b";
    private static final String NAME = "runs/20261022T090000Z-0001/adapter/compliance-basic.json";
    private static final Instant UNTIL = Instant.parse("2026-10-22T11:00:00Z");

    private record Recorded(String method, String path, Map<String, String> query, String body) {
    }

    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
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
