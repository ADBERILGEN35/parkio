package com.parkio.auth.infrastructure.durable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Provider seam of the #483 acceptance harness ({@link GcsAcceptanceTool}). The adapter path is the
 * merged {@link GcsObjectRetentionTransport} per identity; {@link RetentionProbe} holds the raw
 * mutations the adapter deliberately has no operation for (shorten, clear or delete), so the
 * provider's refusals can be observed. The live implementation talks to Google Cloud Storage; the
 * offline model only plans the run and tests the harness, and never counts as #483 evidence.
 */
interface GcsAcceptanceProvider {

    /** The principals of the acceptance operation; each maps to one service account when live. */
    enum Identity {
        /** Production-like store role: create, setRetention, delete (overwrite), get, list, buckets.get. */
        WRITER,
        /** The writer role without {@code storage.objects.delete}; probes what an overwrite needs. */
        WRITER_NO_DELETE,
        /** Read-only recovery role: get, list, buckets.get. */
        READER,
        /** Object admin incl. {@code overrideUnlockedRetention}; only for refusal probes and controls. */
        PROBE_ADMIN
    }

    /** Bucket configuration as the provider reports it. Null means the provider omitted the field. */
    record BucketSnapshot(String name, String projectNumber, String location, String locationType,
                          String storageClass, Boolean versioning, String objectRetentionMode,
                          Long retentionPolicySeconds, Long softDeleteSeconds, Boolean uniformBucketLevelAccess,
                          String publicAccessPrevention, Boolean hierarchicalNamespace, Map<String, String> labels,
                          String timeCreated, Long metageneration) {
    }

    /** One generation as read back: absent, present without retention, or present with a lock. */
    record GenerationState(boolean exists, EvidenceRetentionMode mode, Instant retainUntil) {

        static GenerationState absent() {
            return new GenerationState(false, null, null);
        }
    }

    /** A raw mutation attempt: accepted, or refused with the provider's status, reason and message. */
    record ProbeOutcome(boolean accepted, int code, String reason, String message) {

        static ProbeOutcome ok() {
            return new ProbeOutcome(true, 200, "", "");
        }
    }

    /**
     * Raw operations outside the adapter. Retention changes address the live generation and carry
     * an {@code ifGenerationMatch} precondition: the JSON API patches only the live object.
     */
    interface RetentionProbe {

        BucketSnapshot bucket(String bucket);

        GenerationState state(String bucket, String name, long generation);

        ProbeOutcome setRetention(String bucket, String name, long liveGeneration, EvidenceRetentionMode mode,
                                  Instant retainUntil, boolean overrideUnlockedRetention);

        ProbeOutcome clearRetention(String bucket, String name, long liveGeneration,
                                    boolean overrideUnlockedRetention);

        ProbeOutcome delete(String bucket, String name, long generation);
    }

    /** {@code live-provider} or {@code offline-model}. */
    String evidenceClass();

    /** The merged adapter's transport for {@code identity}; the harness instruments it. */
    GcsObjectRetentionTransport transport(Identity identity, String bucket);

    RetentionProbe probe(Identity identity);

    Clock clock();

    void sleep(Duration duration) throws InterruptedException;

    /** Facts about the provider client for the result (library version, request counts); no secrets. */
    Map<String, Object> describe();
}
