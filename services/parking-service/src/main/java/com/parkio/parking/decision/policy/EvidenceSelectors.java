package com.parkio.parking.decision.policy;

import com.parkio.parking.decision.assessment.EvidenceReference;
import com.parkio.parking.decision.assessment.ReasonCode;
import com.parkio.parking.decision.evidence.EvidenceItem;
import com.parkio.parking.decision.evidence.EvidenceType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Pure helpers for selecting EvidenceItems by type/reason without double-counting. */
final class EvidenceSelectors {

    private EvidenceSelectors() {}

    static List<EvidenceItem> ofType(List<EvidenceItem> items, EvidenceType type) {
        List<EvidenceItem> selected = new ArrayList<>();
        Set<EvidenceItem> seen = new LinkedHashSet<>();
        for (EvidenceItem item : items) {
            if (item.type() == type && seen.add(item)) {
                selected.add(item);
            }
        }
        return selected;
    }

    static Optional<EvidenceItem> firstWithReason(List<EvidenceItem> items, String reason) {
        ReasonCode code = ReasonCode.of(reason);
        for (EvidenceItem item : items) {
            if (item.reasonCode().isPresent() && item.reasonCode().get().equals(code)) {
                return Optional.of(item);
            }
        }
        return Optional.empty();
    }

    static boolean hasReason(List<EvidenceItem> items, String reason) {
        return firstWithReason(items, reason).isPresent();
    }

    static List<EvidenceReference> refs(List<EvidenceItem> items) {
        LinkedHashSet<EvidenceReference> unique = new LinkedHashSet<>();
        for (EvidenceItem item : items) {
            unique.add(EvidenceReference.from(item));
        }
        return List.copyOf(unique);
    }

    static List<EvidenceReference> refsOf(EvidenceItem... items) {
        LinkedHashSet<EvidenceReference> unique = new LinkedHashSet<>();
        for (EvidenceItem item : items) {
            if (item != null) {
                unique.add(EvidenceReference.from(item));
            }
        }
        return List.copyOf(unique);
    }

    static boolean isLegalRiskReason(String reason) {
        return switch (reason) {
            case "AI_RISK_NO_PARKING_SIGN",
                    "AI_RISK_GARAGE_ENTRANCE",
                    "AI_RISK_BUS_STOP",
                    "AI_RISK_PEDESTRIAN_CROSSING",
                    "AI_RISK_FIRE_HYDRANT",
                    "AI_RISK_SIDEWALK",
                    "AI_RISK_TRAFFIC_FLOW_BLOCKING",
                    "AI_RISK_PRIVATE_PROPERTY",
                    "LEGAL_RISK_SCORE",
                    "SUBMITTER_LEGAL_RISK" -> true;
            default -> false;
        };
    }
}