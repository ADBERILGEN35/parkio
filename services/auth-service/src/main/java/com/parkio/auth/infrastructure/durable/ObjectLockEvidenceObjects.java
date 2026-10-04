package com.parkio.auth.infrastructure.durable;

import com.parkio.auth.application.durable.DurableErasureEvidence;
import com.parkio.auth.application.durable.EvidenceObjects;
import io.minio.MinioClient;
import java.util.List;
import java.util.Optional;

/**
 * The evidence objects of an object-lock bucket as the format v2 verifier reads them: records,
 * sequence markers and checkpoints by their first (canonical) version; the frontier, the only
 * object that is rewritten, through all its versions ({@link #findAll}), because the listing
 * order by modification time is not the write order after a backward clock step. Delete
 * markers are ignored. Needs nothing but the bucket, so recovery can run after the primary host
 * and its database are gone.
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

    /** Every version of the frontier. Other objects are read by their canonical version with {@link #find}. */
    @Override
    public List<byte[]> findAll(String key) {
        if (!DurableErasureEvidence.FRONTIER_KEY.equals(key)) {
            throw new IllegalArgumentException("only the frontier is read by all its versions, not " + key);
        }
        return bucket.allVersions(key).stream().map(ObjectLockBucket.StoredVersion::bytes).toList();
    }

    /**
     * The first (canonical) version of a record, marker or checkpoint. Refuses the frontier: no
     * single listed version of it is reliably the current one, so it is read with {@link #findAll}
     * and verified.
     */
    @Override
    public Optional<byte[]> find(String key) {
        if (DurableErasureEvidence.FRONTIER_KEY.equals(key)) {
            throw new IllegalArgumentException("the frontier is read by all its versions (findAll), then verified");
        }
        return bucket.oldest(key).map(ObjectLockBucket.StoredVersion::bytes);
    }
}
