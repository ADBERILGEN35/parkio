package com.parkio.parking.presentation;

import com.parkio.parking.application.MunicipalFacilityQueryService;
import com.parkio.parking.application.RegistryPublicationService;
import com.parkio.parking.presentation.dto.MunicipalFacilityResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/parking/facilities")
public class MunicipalFacilityController {
    private final MunicipalFacilityQueryService service;
    private final RegistryPublicationService registryPublication;

    public MunicipalFacilityController(
            MunicipalFacilityQueryService service,
            RegistryPublicationService registryPublication) {
        this.service = service;
        this.registryPublication = registryPublication;
    }

    @GetMapping("/nearby")
    public List<MunicipalFacilityResponse> nearby(
            @RequestParam double lat,
            @RequestParam double lng,
            @RequestParam(defaultValue = "1000") int radiusMeters,
            @RequestParam(defaultValue = "20") int limit) {
        return service.nearby(lat, lng, radiusMeters, limit).stream()
                .map(view -> MunicipalFacilityResponse.from(
                        view, registryPublication.forFacility(view.id())))
                .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<MunicipalFacilityResponse> byId(@PathVariable UUID id) {
        return service.findById(id).map(view -> MunicipalFacilityResponse.from(
                        view, registryPublication.forFacility(view.id())))
                .map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }
}
