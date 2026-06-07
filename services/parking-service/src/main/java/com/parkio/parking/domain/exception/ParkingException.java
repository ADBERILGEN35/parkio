package com.parkio.parking.domain.exception;

/** Domain exception carrying a {@link ParkingErrorCode}; translated to HTTP in presentation. */
public class ParkingException extends RuntimeException {

    private final ParkingErrorCode errorCode;

    public ParkingException(ParkingErrorCode errorCode) {
        this(errorCode, errorCode.name());
    }

    public ParkingException(ParkingErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ParkingErrorCode errorCode() {
        return errorCode;
    }
}
