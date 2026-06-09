package com.parkio.media.infrastructure.storage;

import com.parkio.media.application.port.MediaStoragePort;
import com.parkio.media.infrastructure.config.MediaProperties;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Stores media bytes in an S3-compatible object store (MinIO) via the configured
 * bucket. Implements {@link MediaStoragePort}; the SDK stays confined to this
 * adapter so the application/domain never see it.
 */
@Component
public class MinioMediaStorageAdapter implements MediaStoragePort {

    private final MinioClient client;
    private final String bucket;

    public MinioMediaStorageAdapter(MinioClient client, MediaProperties properties) {
        this.client = client;
        this.bucket = properties.getStorage().getBucket();
    }

    @Override
    public StoredObject store(String objectKey, byte[] content, String contentType) {
        try {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(new ByteArrayInputStream(content), content.length, -1)
                    .contentType(contentType)
                    .build());
        } catch (Exception e) {
            throw new MediaStorageException("Failed to store object " + objectKey, e);
        }
        return new StoredObject(bucket, objectKey);
    }

    @Override
    public void delete(String objectKey) {
        try {
            client.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
        } catch (Exception e) {
            throw new MediaStorageException("Failed to remove object " + objectKey, e);
        }
    }

    /**
     * Presigned GET-only URL, signed locally with the configured credentials (no
     * network call). Expires after {@code ttl}; never persisted by callers.
     */
    @Override
    public String generatePresignedGetUrl(String objectKey, Duration ttl) {
        try {
            return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectKey)
                    .expiry((int) ttl.toSeconds(), TimeUnit.SECONDS)
                    .build());
        } catch (Exception e) {
            throw new MediaStorageException("Failed to presign GET URL for object " + objectKey, e);
        }
    }
}
