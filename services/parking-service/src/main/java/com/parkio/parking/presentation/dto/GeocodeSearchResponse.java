package com.parkio.parking.presentation.dto;

import java.util.List;

/**
 * Envelope for a forward-geocoding search. Wrapping the list in an object (rather
 * than returning a bare array) keeps the response extensible (e.g. attribution or
 * provider metadata later) without breaking the client.
 */
public record GeocodeSearchResponse(List<GeocodeResultResponse> results) {
}
