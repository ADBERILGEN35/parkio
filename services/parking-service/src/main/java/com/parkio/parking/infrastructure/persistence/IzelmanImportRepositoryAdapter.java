package com.parkio.parking.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.externalsource.izelman.NormalizedRoadsideSegment;
import com.parkio.parking.externalsource.izelman.NormalizedTariffPlan;
import com.parkio.parking.externalsource.izelman.SourceAgeClassification;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class IzelmanImportRepositoryAdapter {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public IzelmanImportRepositoryAdapter(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public UpsertOutcome upsertRoadside(
            UUID sourceId, NormalizedRoadsideSegment row, Instant contentAt,
            SourceAgeClassification age, Instant now) throws Exception {
        var existing = jdbc.sql("SELECT l.segment_id,l.raw_record_hash FROM municipal_roadside_source_links l "
                        + "WHERE l.source_id=:source AND l.external_id=:external")
                .param("source", sourceId).param("external", row.externalId())
                .query((rs, n) -> new Existing(rs.getObject(1, UUID.class), rs.getString(2))).optional();
        if (existing.isPresent() && existing.get().hash().equals(row.rawRecordHash())) {
            jdbc.sql("UPDATE municipal_roadside_source_links SET last_seen_at=:now,active=true,updated_at=:now "
                    + "WHERE source_id=:source AND external_id=:external")
                    .param("now", Timestamp.from(now)).param("source", sourceId).param("external", row.externalId()).update();
            return UpsertOutcome.UNCHANGED;
        }
        UUID id = existing.map(Existing::id).orElseGet(UUID::randomUUID);
        if (existing.isEmpty()) {
            jdbc.sql("INSERT INTO municipal_roadside_segments(id,display_name,district,neighborhood,"
                            + "address_or_description,opening_hours_json,latitude,longitude,capacity_total,geometry_kind,"
                            + "payment_required,source_content_at,source_age_classification,created_at,updated_at) "
                            + "VALUES(:id,:name,:district,:neighborhood,:address,:hours,:lat,:lng,:capacity,:kind,"
                            + ":payment,:content,:age,:now,:now)")
                    .param("id", id).param("name", row.displayName()).param("district", row.district())
                    .param("neighborhood", row.neighborhood()).param("address", row.addressOrDescription())
                    .param("hours", row.openingHoursJson()).param("lat", row.latitude()).param("lng", row.longitude())
                    .param("capacity", row.capacityTotal()).param("kind", row.geometryKind().name())
                    .param("payment", row.paymentRequired()).param("content", contentAt == null ? null : Timestamp.from(contentAt)).param("age", age.name())
                    .param("now", Timestamp.from(now)).update();
            jdbc.sql("INSERT INTO municipal_roadside_source_links(id,segment_id,source_id,external_id,raw_record_hash,"
                            + "source_metadata_json,first_seen_at,last_seen_at,created_at,updated_at) "
                            + "VALUES(:id,:segment,:source,:external,:hash,:metadata,:now,:now,:now,:now)")
                    .param("id", UUID.randomUUID()).param("segment", id).param("source", sourceId)
                    .param("external", row.externalId()).param("hash", row.rawRecordHash())
                    .param("metadata", json.writeValueAsString(row.sourceMetadata())).param("now", Timestamp.from(now)).update();
            return UpsertOutcome.INSERTED;
        }
        jdbc.sql("UPDATE municipal_roadside_segments SET display_name=:name,district=:district,neighborhood=:neighborhood,"
                        + "address_or_description=:address,opening_hours_json=:hours,latitude=:lat,longitude=:lng,"
                        + "capacity_total=:capacity,geometry_kind=:kind,payment_required=:payment,source_content_at=:content,"
                        + "source_age_classification=:age,active=true,updated_at=:now,version=version+1 WHERE id=:id")
                .param("id", id).param("name", row.displayName()).param("district", row.district())
                .param("neighborhood", row.neighborhood()).param("address", row.addressOrDescription())
                .param("hours", row.openingHoursJson()).param("lat", row.latitude()).param("lng", row.longitude())
                .param("capacity", row.capacityTotal()).param("kind", row.geometryKind().name())
                .param("payment", row.paymentRequired()).param("content", contentAt == null ? null : Timestamp.from(contentAt)).param("age", age.name())
                .param("now", Timestamp.from(now)).update();
        jdbc.sql("UPDATE municipal_roadside_source_links SET raw_record_hash=:hash,source_metadata_json=:metadata,"
                        + "last_seen_at=:now,active=true,updated_at=:now WHERE source_id=:source AND external_id=:external")
                .param("hash", row.rawRecordHash()).param("metadata", json.writeValueAsString(row.sourceMetadata()))
                .param("now", Timestamp.from(now)).param("source", sourceId).param("external", row.externalId()).update();
        return UpsertOutcome.UPDATED;
    }

    public UpsertOutcome upsertTariff(
            UUID sourceId, NormalizedTariffPlan plan, Instant contentAt,
            SourceAgeClassification age, Instant now) {
        var existing = jdbc.sql("SELECT id FROM municipal_tariff_plans WHERE source_id=:source AND external_id=:external")
                .param("source", sourceId).param("external", plan.externalId()).query(UUID.class).optional();
        UUID id = existing.orElseGet(UUID::randomUUID);
        if (existing.isEmpty()) {
            jdbc.sql("INSERT INTO municipal_tariff_plans(id,source_id,external_id,plan_name,source_content_at,"
                            + "source_age_classification,currentness,original_text,created_at,updated_at) "
                            + "VALUES(:id,:source,:external,:name,:content,:age,:currentness,:original,:now,:now)")
                    .param("id", id).param("source", sourceId).param("external", plan.externalId())
                    .param("name", plan.planName()).param("content", contentAt == null ? null : Timestamp.from(contentAt)).param("age", age.name())
                    .param("currentness", plan.currentness().name()).param("original", plan.originalText())
                    .param("now", Timestamp.from(now)).update();
        } else {
            jdbc.sql("UPDATE municipal_tariff_plans SET plan_name=:name,source_content_at=:content,"
                            + "source_age_classification=:age,currentness=:currentness,original_text=:original,"
                            + "active=true,updated_at=:now,version=version+1 WHERE id=:id")
                    .param("id", id).param("name", plan.planName()).param("content", contentAt == null ? null : Timestamp.from(contentAt))
                    .param("age", age.name()).param("currentness", plan.currentness().name())
                    .param("original", plan.originalText()).param("now", Timestamp.from(now)).update();
            jdbc.sql("DELETE FROM municipal_tariff_rate_bands WHERE plan_id=:id").param("id", id).update();
        }
        for (var band : plan.bands()) {
            jdbc.sql("INSERT INTO municipal_tariff_rate_bands(id,plan_id,band_order,duration_from_minutes,"
                            + "duration_to_minutes,amount,fee_kind,time_band_label) "
                            + "VALUES(:id,:plan,:ord,:from,:to,:amount,:kind,:label)")
                    .param("id", UUID.randomUUID()).param("plan", id).param("ord", band.order())
                    .param("from", band.durationFromMinutes()).param("to", band.durationToMinutes())
                    .param("amount", band.amount()).param("kind", band.feeKind().name())
                    .param("label", band.label()).update();
        }
        return existing.isEmpty() ? UpsertOutcome.INSERTED : UpsertOutcome.UPDATED;
    }

    public int deactivateMissingRoadside(UUID sourceId, Set<String> seen, Instant now) {
        if (seen.isEmpty()) {
            return 0;
        }
        var active = jdbc.sql("SELECT external_id FROM municipal_roadside_source_links "
                        + "WHERE source_id=:source AND active=true")
                .param("source", sourceId).query(String.class).list();
        int count = 0;
        for (String externalId : active) {
            if (!seen.contains(externalId)) {
                count += jdbc.sql("UPDATE municipal_roadside_segments s SET active=false,updated_at=:now,version=version+1 "
                                + "FROM municipal_roadside_source_links l WHERE l.segment_id=s.id AND l.source_id=:source "
                                + "AND l.external_id=:external AND l.active=true")
                        .param("now", Timestamp.from(now)).param("source", sourceId).param("external", externalId).update();
                jdbc.sql("UPDATE municipal_roadside_source_links SET active=false,updated_at=:now "
                                + "WHERE source_id=:source AND external_id=:external")
                        .param("now", Timestamp.from(now)).param("source", sourceId).param("external", externalId).update();
            }
        }
        return count;
    }

    public void saveRun(UUID syncRunId, UUID sourceId, String type, String filename, String sha,
            String encoding, char delimiter, String fingerprint, Instant contentAt,
            SourceAgeClassification age, boolean dryRun, String report, Instant now) {
        jdbc.sql("INSERT INTO municipal_izelman_import_runs(id,sync_run_id,source_id,data_type,input_filename,"
                        + "input_sha256,encoding,delimiter,schema_fingerprint,source_content_at,"
                        + "source_age_classification,dry_run,quality_report_json,created_at) "
                        + "VALUES(:id,:sync,:source,:type,:file,:sha,:encoding,:delimiter,:fingerprint,:content,"
                        + ":age,:dry,:report,:now)")
                .param("id", UUID.randomUUID()).param("sync", syncRunId).param("source", sourceId)
                .param("type", type).param("file", filename).param("sha", sha).param("encoding", encoding)
                .param("delimiter", String.valueOf(delimiter)).param("fingerprint", fingerprint)
                .param("content", contentAt == null ? null : Timestamp.from(contentAt)).param("age", age.name()).param("dry", dryRun)
                .param("report", report).param("now", Timestamp.from(now)).update();
    }

    public enum UpsertOutcome { INSERTED, UPDATED, UNCHANGED }
    private record Existing(UUID id, String hash) {}
}
