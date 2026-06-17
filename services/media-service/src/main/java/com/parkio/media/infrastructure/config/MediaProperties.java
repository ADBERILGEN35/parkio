package com.parkio.media.infrastructure.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/** Binds {@code parkio.media.*}: upload limits, access-URL TTL and object-store connection. */
@ConfigurationProperties(prefix = "parkio.media")
public class MediaProperties {

    private DataSize maxFileSize = DataSize.ofMegabytes(10);
    private List<String> allowedContentTypes = new ArrayList<>();
    /** Lifetime of generated presigned GET URLs (short-lived by design). */
    private Duration accessUrlTtl = Duration.ofMinutes(5);
    private Storage storage = new Storage();
    private Scanner scanner = new Scanner();

    public DataSize getMaxFileSize() {
        return maxFileSize;
    }

    public void setMaxFileSize(DataSize maxFileSize) {
        this.maxFileSize = maxFileSize;
    }

    public List<String> getAllowedContentTypes() {
        return allowedContentTypes;
    }

    public void setAllowedContentTypes(List<String> allowedContentTypes) {
        this.allowedContentTypes = allowedContentTypes;
    }

    public Duration getAccessUrlTtl() {
        return accessUrlTtl;
    }

    public void setAccessUrlTtl(Duration accessUrlTtl) {
        this.accessUrlTtl = accessUrlTtl;
    }

    public Storage getStorage() {
        return storage;
    }

    public void setStorage(Storage storage) {
        this.storage = storage;
    }

    public Scanner getScanner() {
        return scanner;
    }

    public void setScanner(Scanner scanner) {
        this.scanner = scanner;
    }

    /**
     * Anti-malware scanner (ClamAV/clamd over TCP) settings. When {@link #enabled} is
     * false a pass-through scanner is wired instead (local dev / tests only) — see
     * {@code MediaInfrastructureConfig}. Scanning is fail-closed: a scan that cannot be
     * completed rejects the upload.
     */
    public static class Scanner {

        /** Master switch. Must be true in any environment that serves real users. */
        private boolean enabled = true;
        /** clamd host (the ClamAV container/service). */
        private String host = "localhost";
        /** clamd TCP port (clamd default is 3310). */
        private int port = 3310;
        /** TCP connect timeout to clamd. */
        private Duration connectTimeout = Duration.ofSeconds(2);
        /** Read timeout for the scan reply; bounds how long an upload waits on the scan. */
        private Duration readTimeout = Duration.ofSeconds(10);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }
    }

    /**
     * S3-compatible object-store connection settings. Credentials are injected, never
     * hardcoded. {@link #endpoint} is used for in-process PUT/stat/DELETE; {@link
     * #publicEndpoint} is the host embedded in presigned GET URLs and must match what
     * the browser will request (SigV4 signs the {@code Host} header).
     */
    public static class Storage {

        private String bucket;
        /** Container/cluster-reachable endpoint for SDK object operations. */
        private String endpoint;
        /** Browser-reachable endpoint for presigned GET URL generation. */
        private String publicEndpoint;
        private String accessKey;
        private String secretKey;
        private String region;

        public String getBucket() {
            return bucket;
        }

        public void setBucket(String bucket) {
            this.bucket = bucket;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getPublicEndpoint() {
            return publicEndpoint;
        }

        public void setPublicEndpoint(String publicEndpoint) {
            this.publicEndpoint = publicEndpoint;
        }

        public String getAccessKey() {
            return accessKey;
        }

        public void setAccessKey(String accessKey) {
            this.accessKey = accessKey;
        }

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }
    }
}
