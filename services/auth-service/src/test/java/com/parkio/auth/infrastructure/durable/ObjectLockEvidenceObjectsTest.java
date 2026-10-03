package com.parkio.auth.infrastructure.durable;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.auth.application.durable.DurableErasureEvidence;
import io.minio.MinioClient;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The frontier is the only object read through all of its versions, and only that way: no single
 * listed version of it is reliably the current one. Both refusals happen before any store call.
 */
class ObjectLockEvidenceObjectsTest {

    private final ObjectLockEvidenceObjects objects = new ObjectLockEvidenceObjects(new ObjectLockBucket(
            MinioClient.builder().endpoint("http://127.0.0.1:9").credentials("unused", "unused").build(), "unused"));

    @Test
    void theFrontierIsNotReadAsOneVersion() {
        assertThatThrownBy(() -> objects.find(DurableErasureEvidence.FRONTIER_KEY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> objects.get(DurableErasureEvidence.FRONTIER_KEY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyTheFrontierIsReadByAllItsVersions() {
        assertThatThrownBy(() -> objects.findAll(DurableErasureEvidence.recordKey(UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> objects.findAll(DurableErasureEvidence.sequenceKey(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
