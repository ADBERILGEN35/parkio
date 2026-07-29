/**
 * Availability Engine domain vocabulary inside parking-service.
 *
 * <p>Publication (Decision Engine) answers whether a report may exist.
 * Availability answers whether the parking place is likely still empty.
 *
 * <p>Pipeline: AvailabilityEvidence -> AvailabilityPolicy -> AvailabilityEvaluation.
 * WP-05.9 introduces the pure engine only; search, publication, and persistence
 * integration follow in later work packages.
 *
 * <p>Core types MUST NOT depend on Spring, JPA, Kafka, or presentation DTOs.
 */
package com.parkio.parking.availability;
