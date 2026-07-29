package com.parkio.parking.decision.application;

import com.parkio.parking.decision.evidence.EvidenceItem;
import com.parkio.parking.decision.evidence.EvidenceVector;
import com.parkio.parking.decision.normalization.AiValidationEvidenceNormalizer;
import com.parkio.parking.decision.normalization.EvidenceCollectionRequest;
import com.parkio.parking.decision.normalization.OperationalEvidenceNormalizer;
import com.parkio.parking.decision.normalization.ParkingSpotLocationEvidenceNormalizer;
import com.parkio.parking.decision.port.EvidenceCollectionPort;
import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates repository-grounded evidence normalizers and vector assembly.
 * Side-effect free; does not fetch remote state.
 */
public final class EvidenceCollectionService implements EvidenceCollectionPort {

    private final AiValidationEvidenceNormalizer aiNormalizer;
    private final ParkingSpotLocationEvidenceNormalizer locationNormalizer;
    private final OperationalEvidenceNormalizer operationalNormalizer;
    private final EvidenceVectorFactory vectorFactory;

    public EvidenceCollectionService() {
        this(
                new AiValidationEvidenceNormalizer(),
                new ParkingSpotLocationEvidenceNormalizer(),
                new OperationalEvidenceNormalizer(),
                new EvidenceVectorFactory());
    }

    EvidenceCollectionService(
            AiValidationEvidenceNormalizer aiNormalizer,
            ParkingSpotLocationEvidenceNormalizer locationNormalizer,
            OperationalEvidenceNormalizer operationalNormalizer,
            EvidenceVectorFactory vectorFactory) {
        this.aiNormalizer = aiNormalizer;
        this.locationNormalizer = locationNormalizer;
        this.operationalNormalizer = operationalNormalizer;
        this.vectorFactory = vectorFactory;
    }

    @Override
    public EvidenceVector collect(EvidenceCollectionRequest request) {
        List<EvidenceItem> items = new ArrayList<>();
        items.addAll(aiNormalizer.normalize(request.aiValidation()));
        items.addAll(operationalNormalizer.normalize(
                request.aiValidation(), request.optionalSpotContext()));
        request.optionalSpotContext().ifPresent(context -> items.addAll(
                locationNormalizer.normalize(context, request.collectedAt())));

        return vectorFactory.assemble(
                request.parkingSpotId(),
                request.evaluationId(),
                request.collectedAt(),
                items);
    }
}
