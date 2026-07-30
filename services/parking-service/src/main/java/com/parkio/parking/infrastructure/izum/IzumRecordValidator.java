package com.parkio.parking.infrastructure.izum;

import com.parkio.parking.externalsource.validation.MunicipalRecordValidation;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class IzumRecordValidator {
    public MunicipalRecordValidation validate(IzumParkingRecordDto record) {
        List<String> errors = new ArrayList<>();
        if (record == null) return MunicipalRecordValidation.invalid(List.of("record_missing"));
        if (record.ufid() == null || record.ufid().isBlank()) errors.add("ufid_missing");
        if (record.lat() == null || !Double.isFinite(record.lat()) || record.lat() < -90 || record.lat() > 90) {
            errors.add("latitude_invalid");
        }
        if (record.lng() == null || !Double.isFinite(record.lng()) || record.lng() < -180 || record.lng() > 180) {
            errors.add("longitude_invalid");
        }
        if (record.occupancy() == null || record.occupancy().total() == null) {
            errors.add("occupancy_missing");
        } else {
            Integer free = record.occupancy().total().free();
            Integer occupied = record.occupancy().total().occupied();
            if (free != null && free < 0) errors.add("free_negative");
            if (occupied != null && occupied < 0) errors.add("occupied_negative");
        }
        return errors.isEmpty() ? MunicipalRecordValidation.success() : MunicipalRecordValidation.invalid(errors);
    }
}
