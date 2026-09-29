package com.parkio.parking.application.quality;

/** Requested source key has no modelled quality section; surfaced as HTTP 404. */
public class UnknownQualityReportSourceException extends IllegalArgumentException {
    public UnknownQualityReportSourceException(String message) {
        super(message);
    }
}
