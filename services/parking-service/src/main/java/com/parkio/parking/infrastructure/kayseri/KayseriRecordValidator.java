package com.parkio.parking.infrastructure.kayseri;

import com.parkio.parking.externalsource.validation.MunicipalRecordValidation;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class KayseriRecordValidator {
    private final KayseriGeoValidator geoValidator;

    public KayseriRecordValidator(KayseriGeoValidator geoValidator) {
        this.geoValidator = geoValidator;
    }

    public MunicipalRecordValidation validate(KayseriParkingRecordDto record) {
        List<String> errors = new ArrayList<>();
        if (record == null) {
            return MunicipalRecordValidation.invalid(List.of("record_missing"));
        }
        if (record.cbno() == null || record.cbno().isBlank()) {
            errors.add("id_missing");
        }
        String name = displayName(record);
        if (name == null || name.isBlank()) {
            errors.add("name_missing");
        } else if (containsReplacementChar(name)) {
            errors.add("name_encoding_invalid");
        }
        if (record.latDd() == null
                || record.lonDd() == null
                || !geoValidator.isValid(record.latDd(), record.lonDd())) {
            errors.add("coordinates_invalid");
        }
        return errors.isEmpty()
                ? MunicipalRecordValidation.success()
                : MunicipalRecordValidation.invalid(errors);
    }

    static String displayName(KayseriParkingRecordDto record) {
        if (record.adi() != null && !record.adi().isBlank()) {
            return record.adi().trim();
        }
        if (record.kisaAdi() != null && !record.kisaAdi().isBlank()) {
            return record.kisaAdi().trim();
        }
        return null;
    }

    private static boolean containsReplacementChar(String value) {
        return value.indexOf('\uFFFD') >= 0;
    }
}
