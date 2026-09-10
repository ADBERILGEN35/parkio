package com.parkio.parking.infrastructure.ispark;

import com.parkio.parking.externalsource.validation.MunicipalRecordValidation;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class IsparkRecordValidator {
    /** Approximate Türkiye bounding box for facility coordinates. */
    static final double TR_LAT_MIN = 35.8;
    static final double TR_LAT_MAX = 42.3;
    static final double TR_LNG_MIN = 25.5;
    static final double TR_LNG_MAX = 45.0;

    public MunicipalRecordValidation validate(IsparkParkingRecordDto record) {
        List<String> errors = new ArrayList<>();
        if (record == null) {
            return MunicipalRecordValidation.invalid(List.of("record_missing"));
        }
        if (record.parkID() == null || record.parkID() <= 0) {
            errors.add("park_id_missing");
        }
        if (record.parkName() == null || record.parkName().isBlank()) {
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
        if (record.capacity() == null || record.capacity() < 0) {
            errors.add("capacity_invalid");
        }
        if (record.emptyCapacity() == null || record.emptyCapacity() < 0) {
            errors.add("empty_capacity_invalid");
        }
        if (record.capacity() != null
                && record.emptyCapacity() != null
                && record.emptyCapacity() > record.capacity()) {
            errors.add("empty_exceeds_capacity");
        }
        return errors.isEmpty()
                ? MunicipalRecordValidation.success()
                : MunicipalRecordValidation.invalid(errors);
    }
}
