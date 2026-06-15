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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Stores media bytes in an S3-compatible object store (MinIO) via the configured
 * bucket. Implements {@link MediaStoragePort}; the SDK stays confined to this
 * adapter so the application/domain never see it.
 *
 * <p>Object operations use the internal endpoint; presigned GET URLs are generated
 * with the public endpoint so the signed {@code Host} matches the browser request.
 */
@Component
public class MinioMediaStorageAdapter implements MediaStoragePort {

    private final MinioClient internalClient;
    private final MinioClient presignClient;
    private final String bucket;

    public MinioMediaStorageAdapter(
            @Qualifier("internalMinioClient") MinioClient internalClient,
            @Qualifier("presignMinioClient") MinioClient presignClient,
            MediaProperties properties) {
        this.internalClient = internalClient;
        this.presignClient = presignClient;
        this.bucket = properties.getStorage().getBucket();
    }

    @Override
    public StoredObject store(String objectKey, byte[] content, String contentType) {
        try {
            internalClient.putObject(PutObjectArgs.builder()
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
            internalClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
        } catch (Exception e) {
            throw new MediaStorageException("Failed to remove object " + objectKey, e);
        }
    }

    /**
     * Presigned GET-only URL, signed locally with the configured credentials (no
     * network call). Uses the public endpoint client so the embedded host matches
     * what the browser will request. Expires after {@code ttl}; never persisted by
     * callers.
     */
    @Override
    public String generatePresignedGetUrl(String objectKey, Duration ttl) {
        try {
            return presignClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
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
