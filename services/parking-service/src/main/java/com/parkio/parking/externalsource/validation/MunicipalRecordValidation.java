package com.parkio.parking.externalsource.validation;

import java.util.List;

public record MunicipalRecordValidation(boolean valid, List<String> errors) {
    public static MunicipalRecordValidation success() {
        return new MunicipalRecordValidation(true, List.of());
    }

    public static MunicipalRecordValidation invalid(List<String> errors) {
        return new MunicipalRecordValidation(false, List.copyOf(errors));
    }
}
