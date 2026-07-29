package com.parkio.parking.infrastructure.persistence.trust;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.infrastructure.persistence.entity.TrustSnapshotEntity;
import com.parkio.parking.trust.TrustConfidence;
import com.parkio.parking.trust.TrustDomain;
import com.parkio.parking.trust.TrustScore;
import com.parkio.parking.trust.TrustSnapshot;
import com.parkio.parking.trust.TrustSnapshotSchemaVersion;
import com.parkio.parking.trust.TrustSubject;
import com.parkio.parking.trust.TrustSubjectType;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TrustPersistenceMapperTest {

    private final TrustPersistenceMapper mapper = new TrustPersistenceMapper(new ObjectMapper());

    @Test
    void serializesAndDeserializesSnapshotStably() {
        TrustSnapshot snapshot = new TrustSnapshot(
                new TrustSubject(TrustSubjectType.REPORTER, UUID.randomUUID()),
                TrustDomain.PARKING_REPORT_ACCURACY,
                "trust-policy-v1",
                TrustSnapshotSchemaVersion.V1,
                TrustScore.of(5_800),
                TrustConfidence.of(3_700),
                800,
                200,
                4,
                TrustSnapshot.Level.DEVELOPING,
                Instant.parse("2026-07-28T10:00:00Z"));

        TrustSnapshotEntity entity = mapper.toEntity(
                snapshot,
                Instant.parse("2026-07-28T10:00:00Z"),
                Instant.parse("2026-07-28T10:00:01Z"),
                2L);
        TrustSnapshot restored = mapper.toDomain(entity);

        assertThat(restored).isEqualTo(snapshot);
    }

    @Test
    void unknownSnapshotSchemaFailsExplicitly() {
        TrustSnapshotEntity entity = new TrustSnapshotEntity(
                UUID.randomUUID(),
                TrustSubjectType.REPORTER.name(),
                UUID.randomUUID(),
                TrustDomain.PARKING_REPORT_ACCURACY.name(),
                "trust-policy-v1",
                "trust-snapshot-v999",
                Instant.parse("2026-07-28T10:00:00Z"),
                """
                {"subjectType":"REPORTER","subjectId":"00000000-0000-0000-0000-000000000001","domain":"PARKING_REPORT_ACCURACY","trustPolicyVersion":"trust-policy-v1","snapshotSchemaVersion":"trust-snapshot-v999","scoreBasisPoints":5000,"confidenceBasisPoints":0,"positiveEvidenceMass":0,"negativeEvidenceMass":0,"effectiveEvidenceCount":0,"level":"UNKNOWN","lastEvaluatedAt":"2026-07-28T10:00:00Z"}
                """,
                Instant.parse("2026-07-28T10:00:00Z"),
                Instant.parse("2026-07-28T10:00:00Z"),
                0L);

        assertThatThrownBy(() -> mapper.toDomain(entity))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown trust snapshot schema version");
    }
}
