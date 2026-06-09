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

    /** S3-compatible object-store connection settings. Credentials are injected, never hardcoded. */
    public static class Storage {

        private String bucket;
        private String endpoint;
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
