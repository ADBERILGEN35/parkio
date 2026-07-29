package com.parkio.parking.infrastructure.persistence.mapper;

import com.parkio.parking.domain.ParkingSpot;
import com.parkio.parking.domain.ParkingSpotRejection;
import com.parkio.parking.domain.ParkingSpotSearchLog;
import com.parkio.parking.domain.ParkingSpotStatusHistory;
import com.parkio.parking.domain.ParkingSpotVerification;
import com.parkio.parking.domain.ParkingSpotViewLog;
import com.parkio.parking.domain.RejectionReasonCode;
import com.parkio.parking.domain.RejectionSource;
import com.parkio.parking.domain.VehicleType;
import com.parkio.parking.domain.ViolationReason;
import com.parkio.parking.infrastructure.persistence.entity.ParkingSpotEntity;
import com.parkio.parking.infrastructure.persistence.entity.ParkingSpotSearchLogEntity;
import com.parkio.parking.infrastructure.persistence.entity.ParkingSpotStatusHistoryEntity;
import com.parkio.parking.infrastructure.persistence.entity.ParkingSpotVerificationEntity;
import com.parkio.parking.infrastructure.persistence.entity.ParkingSpotViewLogEntity;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Translates between domain models and JPA entities. Enum sets are stored as
 * comma-separated strings (no extra tables); the domain stays persistence-agnostic.
 */
public final class ParkingPersistenceMapper {

    private ParkingPersistenceMapper() {
    }

    public static ParkingSpot toDomain(ParkingSpotEntity e) {
        return new ParkingSpot(e.getId(), e.getOwnerUserId(), e.getMediaId(), e.getLatitude(), e.getLongitude(),
                e.getAddressText(), e.getDescription(), e.isManualLocationEdited(),
                splitVehicleTypes(e.getSuitableVehicleTypes()), e.getParkingContext(), e.getLegalStatus(),
                splitViolationReasons(e.getViolationReasons()), e.getStatus(), e.getConfidenceScore(),
                e.getVerificationCount(), e.getFilledReportCount(), e.getExpiresAt(), e.getCreatedAt(),
                e.getUpdatedAt(), e.getVersion(), e.getActivatedAt(), e.getModerationDeadlineAt(),
                e.getModerationAttempts(), e.getModerationDecidedAt(), e.getModerationRequestId(),
                e.getReviewSlaBreachedAt(), toRejection(e), e.getLastAiPolicyVersion());
    }

    public static ParkingSpotEntity toEntity(ParkingSpot s) {
        ParkingSpotRejection rejection = s.rejection();
        return new ParkingSpotEntity(s.id(), s.ownerUserId(), s.mediaId(), s.latitude(), s.longitude(),
                s.addressText(), s.description(), s.manualLocationEdited(),
                joinEnums(s.suitableVehicleTypes()), s.parkingContext(), s.legalStatus(),
                emptyToNull(joinEnums(s.violationReasons())), s.status(), s.confidenceScore(),
                s.verificationCount(), s.filledReportCount(), s.expiresAt(), s.createdAt(),
                s.updatedAt(), s.version(), s.activatedAt(), s.moderationDeadlineAt(),
                s.moderationAttempts(), s.moderationDecidedAt(), s.moderationRequestId(),
                s.reviewSlaBreachedAt(),
                rejection == null ? null : rejection.code().name(),
                rejection == null ? null : rejection.message(),
                rejection == null ? null : rejection.source().name(),
                rejection == null ? null : rejection.rejectedAt(),
                rejection == null ? null : rejection.rejectedBy(),
                rejection == null ? null : rejection.policyVersion(),
                s.lastAiPolicyVersion());
    }

    private static ParkingSpotRejection toRejection(ParkingSpotEntity e) {
        if (e.getRejectionReasonCode() == null || e.getRejectionReasonCode().isBlank()) {
            return null;
        }
        RejectionReasonCode code;
        try {
            code = RejectionReasonCode.valueOf(e.getRejectionReasonCode());
        } catch (IllegalArgumentException ignored) {
            code = RejectionReasonCode.OTHER;
        }
        RejectionSource source = RejectionSource.AI_POLICY;
        if (e.getRejectionSource() != null && !e.getRejectionSource().isBlank()) {
            try {
                source = RejectionSource.valueOf(e.getRejectionSource());
            } catch (IllegalArgumentException ignored) {
                source = RejectionSource.AI_POLICY;
            }
        }
        String message = e.getRejectionReasonText();
        if (message == null || message.isBlank()) {
            message = com.parkio.parking.domain.RejectionReasonCatalog.messageTr(code);
        }
        java.time.Instant rejectedAt = e.getRejectedAt() != null ? e.getRejectedAt() : e.getUpdatedAt();
        return new ParkingSpotRejection(
                code, message, source, rejectedAt, e.getRejectedBy(), e.getRejectionPolicyVersion());
    }

    public static ParkingSpotVerification toDomain(ParkingSpotVerificationEntity e) {
        return new ParkingSpotVerification(e.getId(), e.getSpotId(), e.getVerifierUserId(),
                e.getResult(), e.getCreatedAt());
    }

    public static ParkingSpotVerificationEntity toEntity(ParkingSpotVerification v) {
        return new ParkingSpotVerificationEntity(v.id(), v.spotId(), v.verifierUserId(),
                v.result(), v.createdAt());
    }

    public static ParkingSpotStatusHistoryEntity toEntity(ParkingSpotStatusHistory h) {
        return new ParkingSpotStatusHistoryEntity(h.id(), h.spotId(), h.previousStatus(),
                h.newStatus(), h.reason(), h.createdAt());
    }

    public static ParkingSpotViewLogEntity toEntity(ParkingSpotViewLog v) {
        return new ParkingSpotViewLogEntity(v.id(), v.spotId(), v.viewerUserId(), v.createdAt());
    }

    public static ParkingSpotSearchLogEntity toEntity(ParkingSpotSearchLog s) {
        return new ParkingSpotSearchLogEntity(s.id(), s.searcherUserId(), s.latitude(), s.longitude(),
                s.radiusMeters(), s.resultCount(), s.createdAt());
    }

    private static <E extends Enum<E>> String joinEnums(Set<E> values) {
        return values.stream().map(Enum::name).collect(Collectors.joining(","));
    }

    private static Set<VehicleType> splitVehicleTypes(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(VehicleType::valueOf)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<ViolationReason> splitViolationReasons(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(ViolationReason::valueOf)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
