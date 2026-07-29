/**
 * Decision Engine domain vocabulary and ports inside parking-service.
 *
 * <p>Pipeline: EvidenceVector -> EvidenceEvaluationPolicy -> AssessmentBundle ->
 * RiskAssessmentPolicy -> DecisionPort. WP-05.4 defines assessment types and
 * mathematics and WP-05.5 shadow Decision Engine; production publication authority remains applyAiValidationResult.
 *
 * <p>Core types MUST NOT depend on Spring, JPA, Kafka, or presentation DTOs.
 */
package com.parkio.parking.decision;