package com.parkio.parking.decision.policy;

import com.parkio.parking.decision.assessment.AssessmentVersion;
import com.parkio.parking.decision.assessment.EvidenceReference;
import com.parkio.parking.decision.assessment.ReasonCode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Immutable hard-constraint evaluation outcome for one AssessmentBundle. */
public final class HardConstraintResult {

    private final boolean active;
    private final List<ReasonCode> reasonCodes;
    private final List<EvidenceReference> contributingEvidence;
    private final AssessmentVersion policyVersion;

    private HardConstraintResult(
            boolean active,
            List<ReasonCode> reasonCodes,
            List<EvidenceReference> contributingEvidence,
            AssessmentVersion policyVersion) {
        this.active = active;
        this.reasonCodes = copyReasons(reasonCodes);
        this.contributingEvidence = copyRefs(contributingEvidence);
        this.policyVersion = Objects.requireNonNull(policyVersion, "policyVersion");
        if (active && this.reasonCodes.isEmpty()) {
            throw new IllegalArgumentException("active hard constraint requires reason codes");
        }
    }

    public static HardConstraintResult inactive(AssessmentVersion policyVersion) {
        return new HardConstraintResult(false, List.of(), List.of(), policyVersion);
    }

    public static HardConstraintResult active(
            List<ReasonCode> reasonCodes,
            List<EvidenceReference> contributingEvidence,
            AssessmentVersion policyVersion) {
        return new HardConstraintResult(true, reasonCodes, contributingEvidence, policyVersion);
    }

    public boolean active() {
        return active;
    }

    public List<ReasonCode> reasonCodes() {
        return reasonCodes;
    }

    public List<EvidenceReference> contributingEvidence() {
        return contributingEvidence;
    }

    public AssessmentVersion policyVersion() {
        return policyVersion;
    }

    private static List<ReasonCode> copyReasons(List<ReasonCode> reasonCodes) {
        Objects.requireNonNull(reasonCodes, "reasonCodes");
        List<ReasonCode> copy = new ArrayList<>(reasonCodes.size());
        for (ReasonCode code : reasonCodes) {
            if (code == null) {
                throw new IllegalArgumentException("reasonCodes must not contain null");
            }
            copy.add(code);
        }
        return Collections.unmodifiableList(copy);
    }

    private static List<EvidenceReference> copyRefs(List<EvidenceReference> refs) {
        Objects.requireNonNull(refs, "contributingEvidence");
        LinkedHashSet<EvidenceReference> unique = new LinkedHashSet<>();
        for (EvidenceReference ref : refs) {
            if (ref == null) {
                throw new IllegalArgumentException("contributingEvidence must not contain null");
            }
            unique.add(ref);
        }
        return Collections.unmodifiableList(new ArrayList<>(unique));
    }
}