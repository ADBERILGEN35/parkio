package com.parkio.aivalidation.domain;

/**
 * Rich classification outcome for content risk. {@link #verdict()} drives the
 * publication gate; {@link #outcomeKind()} distinguishes a genuine model verdict
 * from infrastructure fail-closed so reuse / recovery can treat them differently.
 */
public record ContentClassification(
        ContentRiskClassifier.Verdict verdict,
        OutcomeKind outcomeKind,
        String reasonCode,
        String claimedRegionAssessment,
        String vehicleFitEstimate,
        String obstructionAssessment,
        String legalityAccessAssessment,
        ModerationProvenance provenance) {

    public enum OutcomeKind {
        /** Model returned a semantic verdict (including genuine UNCERTAIN). */
        SEMANTIC,
        /** Fail-closed due to timeout, outage, quota, malformed response, circuit open, etc. */
        INFRASTRUCTURE
    }

    public ContentClassification {
        // compact ctor — fields may be null except verdict/outcomeKind
    }

    public static ContentClassification semantic(ContentRiskClassifier.Verdict verdict, String reasonCode) {
        return semantic(verdict, reasonCode, null, null, null, null);
    }

    public static ContentClassification semantic(ContentRiskClassifier.Verdict verdict,
                                                 String reasonCode,
                                                 String claimedRegionAssessment,
                                                 String vehicleFitEstimate,
                                                 String obstructionAssessment,
                                                 String legalityAccessAssessment) {
        return new ContentClassification(
                verdict, OutcomeKind.SEMANTIC, reasonCode,
                claimedRegionAssessment, vehicleFitEstimate,
                obstructionAssessment, legalityAccessAssessment, null);
    }

    public static ContentClassification infrastructure(String reasonCode) {
        return new ContentClassification(
                ContentRiskClassifier.Verdict.UNCERTAIN, OutcomeKind.INFRASTRUCTURE, reasonCode,
                null, null, null, null, null);
    }

    /** Returns a copy carrying the given provenance (verdict/assessments unchanged). */
    public ContentClassification withProvenance(ModerationProvenance provenance) {
        return new ContentClassification(verdict, outcomeKind, reasonCode, claimedRegionAssessment,
                vehicleFitEstimate, obstructionAssessment, legalityAccessAssessment, provenance);
    }
}
