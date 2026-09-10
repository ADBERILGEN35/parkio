package com.parkio.parking.infrastructure.anpark;

import com.parkio.parking.externalsource.validation.MunicipalRecordValidation;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class AnparkRecordValidator {
    /** Approximate Türkiye bounding box for facility coordinates. */
    static final double TR_LAT_MIN = 35.8;
    static final double TR_LAT_MAX = 42.3;
    static final double TR_LNG_MIN = 25.5;
    static final double TR_LNG_MAX = 45.0;

    public MunicipalRecordValidation validate(AnparkParkingRecordDto record) {
        List<String> errors = new ArrayList<>();
        if (record == null) {
            return MunicipalRecordValidation.invalid(List.of("record_missing"));
        }
        if (record.id() == null || record.id().isBlank()) {
            errors.add("id_missing");
        }
        if (record.name() == null || record.name().isBlank()) {
            errors.add("name_missing");
        }
        if (record.lat() == null || !Double.isFinite(record.lat())
                || record.lat() < TR_LAT_MIN || record.lat() > TR_LAT_MAX) {
            errors.add("latitude_invalid");
        }
        if (record.lng() == null || !Double.isFinite(record.lng())
                || record.lng() < TR_LNG_MIN || record.lng() > TR_LNG_MAX) {
            errors.add("longitude_invalid");
        }
        // capacity <= 0 is normalized to unknown (null); only reject negatives when present.
        if (record.capacity() != null && record.capacity() < 0) {
            errors.add("capacity_invalid");
        }
        return errors.isEmpty()
                ? MunicipalRecordValidation.success()
                : MunicipalRecordValidation.invalid(errors);
    }
}
