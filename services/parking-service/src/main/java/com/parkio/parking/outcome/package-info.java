/**
 * Outcome Validation domain vocabulary inside parking-service.
 *
 * <p>Decision answers whether a report may be published.
 * Availability answers whether a place is likely still empty.
 * Outcome answers whether the report was historically correct.
 *
 * <p>Pipeline: OutcomeEvidence + OutcomeTimeline -> OutcomePolicy -> OutcomeEvaluation.
 * WP-05.10 introduces the pure validator; trust and rewards consume outcomes later.
 *
 * <p>Core types MUST NOT depend on Spring, JPA, Kafka, or presentation DTOs.
 */
package com.parkio.parking.outcome;