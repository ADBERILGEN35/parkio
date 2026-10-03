package com.parkio.auth.infrastructure.durable;

import com.parkio.auth.application.durable.DurableErasureEvidence;
import com.parkio.auth.application.durable.EvidenceObjects;
import io.minio.MinioClient;
import java.util.List;
import java.util.Optional;

/**
 * The evidence objects of an object-lock bucket as the format v1 verifier reads them: records,
 * sequence markers and checkpoints by their first (canonical) version; the frontier, the only
 * object that is rewritten, by its latest version. Delete markers are ignored. Needs nothing
 * but the bucket, so recovery can run after the primary host and its database are gone.
 */
public final class ObjectLockEvidenceObjects implements EvidenceObjects {

    private final ObjectLockBucket bucket;

    ObjectLockEvidenceObjects(ObjectLockBucket bucket) {
        this.bucket = bucket;
    }

    /** Read-only access for recovery tooling that has only the bucket and its credentials. */
    public static ObjectLockEvidenceObjects connect(MinioClient client, String bucket) {
        return new ObjectLockEvidenceObjects(new ObjectLockBucket(client, bucket));
    }

    @Override
    public List<String> list(String prefix) {
        return bucket.keys(prefix);
    }

    @Override
    public Optional<byte[]> find(String key) {
        Optional<ObjectLockBucket.StoredVersion> version = DurableErasureEvidence.FRONTIER_KEY.equals(key)
                ? bucket.latest(key)
                : bucket.oldest(key);
        return version.map(ObjectLockBucket.StoredVersion::bytes);
    }
}
