package com.parkio.parking.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.application.port.MunicipalSourceLinkRepository;
import com.parkio.parking.externalsource.NormalizedMunicipalFacility;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MunicipalSourceLinkRepositoryAdapter implements MunicipalSourceLinkRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public MunicipalSourceLinkRepositoryAdapter(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public UUID upsert(UUID facilityId, UUID sourceId, NormalizedMunicipalFacility value, Instant seenAt) {
        String metadata;
        try {
            metadata = objectMapper.writeValueAsString(value.sourceMetadata());
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Cannot serialize source metadata", ex);
        }
        return jdbc.sql("""
                INSERT INTO municipal_facility_source_links
                    (id,facility_id,source_id,external_id,source_name,source_latitude,source_longitude,
                     source_metadata_json,raw_record_hash,first_seen_at,last_seen_at,last_successful_sync_at,
                     active,created_at,updated_at)
                VALUES (:id,:facilityId,:sourceId,:externalId,:name,:lat,:lng,
                        CAST(:metadata AS jsonb),:hash,:seen,:seen,:seen,true,:seen,:seen)
                ON CONFLICT (source_id,external_id) DO UPDATE SET
                    facility_id=EXCLUDED.facility_id,source_name=EXCLUDED.source_name,
                    source_latitude=EXCLUDED.source_latitude,source_longitude=EXCLUDED.source_longitude,
                    source_metadata_json=EXCLUDED.source_metadata_json,raw_record_hash=EXCLUDED.raw_record_hash,
                    last_seen_at=EXCLUDED.last_seen_at,last_successful_sync_at=EXCLUDED.last_successful_sync_at,
                    active=true,updated_at=EXCLUDED.updated_at
                RETURNING id
                """).param("id", UUID.randomUUID()).param("facilityId", facilityId).param("sourceId", sourceId)
                .param("externalId", value.externalId()).param("name", value.displayName())
                .param("lat", value.latitude()).param("lng", value.longitude()).param("metadata", metadata)
                .param("hash", value.rawRecordHash()).param("seen", Timestamp.from(seenAt)).query(UUID.class).single();
    }
}
