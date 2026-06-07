package com.parkio.parking.application.port;

import com.parkio.parking.domain.ParkingSpotVerification;
import java.util.UUID;

/** Persistence port for {@link ParkingSpotVerification}. */
public interface ParkingSpotVerificationRepository {

    ParkingSpotVerification save(ParkingSpotVerification verification);

    boolean existsBySpotIdAndVerifierUserId(UUID spotId, UUID verifierUserId);
}
