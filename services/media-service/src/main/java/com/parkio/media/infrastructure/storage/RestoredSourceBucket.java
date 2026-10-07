package com.parkio.media.infrastructure.storage;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The one restored source bucket an isolated recovery maps onto the configured bucket (U02;
 * coordinator choice, option A). The isolated restore brings the backup's objects back into the
 * ticket's own bucket ({@code <project>-media}), while the restored {@code media_files} rows and
 * write-ledger rows still name the bucket they were written to. Account erasure lists a key named
 * in that source bucket in the configured bucket instead, so the restored objects can be deleted
 * and their deletion confirmed. Every other bucket stays refused.
 *
 * <p>Fail-closed configuration, checked when the storage adapter is created (so a bad value stops
 * the service from starting):
 * <ul>
 *   <li>set while {@code parkio.privacy.restore-replay.enabled} is false: refused, never ignored;</li>
 *   <li>empty or not an S3 bucket name: refused;</li>
 *   <li>equal to the configured bucket: refused.</li>
 * </ul>
 * It never applies to uploads, reads, presigned URLs or listings, which always use the configured
 * bucket.
 */
final class RestoredSourceBucket {

    static final String PROPERTY = "parkio.media.storage.restored-source-bucket";
    /** S3 bucket naming: 3 to 63 lowercase letters, digits, dots and hyphens, letter or digit at both ends. */
    private static final Pattern BUCKET_NAME = Pattern.compile("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]");

    private RestoredSourceBucket() {
    }

    /** The validated source bucket, or empty when the property is unset. */
    static Optional<String> of(String configured, String configuredBucket, boolean restoreReplayEnabled) {
        if (configured == null) {
            return Optional.empty();
        }
        if (!restoreReplayEnabled) {
            throw new IllegalStateException(PROPERTY + " is set but parkio.privacy.restore-replay.enabled is false;"
                    + " it is honoured only during an isolated recovery");
        }
        if (!BUCKET_NAME.matcher(configured).matches()) {
            throw new IllegalStateException(PROPERTY + " is not a valid bucket name");
        }
        if (configured.equals(configuredBucket)) {
            throw new IllegalStateException(PROPERTY + " must name the restored source bucket, not the configured bucket");
        }
        return Optional.of(configured);
    }
}
