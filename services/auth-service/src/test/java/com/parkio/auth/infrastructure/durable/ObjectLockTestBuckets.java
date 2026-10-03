package com.parkio.auth.infrastructure.durable;

import com.parkio.auth.application.durable.DurableErasureEvidence;
import com.parkio.auth.application.durable.DurableErasureEvidenceVerifier;
import com.parkio.auth.application.durable.DurableErasureEvidenceVerifier.VerifiedFrontier;
import com.parkio.auth.application.durable.DurableEvidenceException;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.errors.ErrorResponseException;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/**
 * Disposable MinIO helpers for the object-lock ITs. MinIO answers {@code /minio/health/ready}
 * before every subsystem is up (and again right after a docker unpause): object-lock bucket
 * creation can then fail with XMinioServerNotInitialized, so it is retried for a bounded time.
 */
public final class ObjectLockTestBuckets {

    private static final Duration PATIENCE = Duration.ofSeconds(60);

    private ObjectLockTestBuckets() {
    }

    public static void createLockedBucket(MinioClient client, String bucket) throws Exception {
        long deadline = System.nanoTime() + PATIENCE.toNanos();
        while (true) {
            try {
                if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                    client.makeBucket(MakeBucketArgs.builder().bucket(bucket).objectLock(true).build());
                }
                return;
            } catch (ErrorResponseException ex) {
                if (!"XMinioServerNotInitialized".equals(ex.errorResponse().code()) || System.nanoTime() > deadline) {
                    throw ex;
                }
                Thread.sleep(250);
            }
        }
    }

    /**
     * The bytes of the frontier version a store treats as current: its highest verified version.
     * Never the first listed version, which a backward clock step on the store host can make stale.
     */
    static byte[] currentFrontier(ObjectLockBucket bucket, DurableErasureEvidenceVerifier verifier) {
        byte[] current = null;
        VerifiedFrontier highest = null;
        for (ObjectLockBucket.StoredVersion version : bucket.allVersions(DurableErasureEvidence.FRONTIER_KEY)) {
            VerifiedFrontier frontier;
            try {
                frontier = verifier.verifyFrontier(Optional.of(version.bytes())).orElseThrow();
            } catch (DurableEvidenceException ignored) {
                continue;
            }
            if (highest == null || frontier.expectedThrough() > highest.expectedThrough()
                    || (frontier.expectedThrough() == highest.expectedThrough()
                            && frontier.highestReserved() > highest.highestReserved())) {
                highest = frontier;
                current = version.bytes();
            }
        }
        if (current == null) {
            throw new AssertionError("no verified frontier version");
        }
        return current;
    }

    public static void awaitReady(String endpoint) throws Exception {
        long deadline = System.nanoTime() + PATIENCE.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                HttpURLConnection connection =
                        (HttpURLConnection) URI.create(endpoint + "/minio/health/ready").toURL().openConnection();
                connection.setConnectTimeout(2000);
                connection.setReadTimeout(2000);
                if (connection.getResponseCode() == 200) {
                    return;
                }
            } catch (IOException ignored) {
                // not ready yet
            }
            Thread.sleep(250);
        }
        throw new IllegalStateException("MinIO did not become ready");
    }
}
