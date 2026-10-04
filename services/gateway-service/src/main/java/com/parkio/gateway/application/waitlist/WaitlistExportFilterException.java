package com.parkio.gateway.application.waitlist;

/** The confirmed export was asked for with a filter it does not support. */
public class WaitlistExportFilterException extends RuntimeException {

    public WaitlistExportFilterException(String message) {
        super(message);
    }
}
