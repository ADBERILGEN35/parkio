package com.parkio.parking.presentation;

import com.parkio.parking.application.PublicExploreQueryService;
import com.parkio.parking.presentation.dto.PublicExploreFacilityMapper;
import com.parkio.parking.presentation.dto.PublicExploreFacilityResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

@RestController
@RequestMapping("/api/v1/public/explore/facilities")
@ConditionalOnProperty(prefix = "parkio.public-explore", name = "enabled", havingValue = "true")
public class PublicExploreController {
    private static final String LIST_CACHE = "public, max-age=30, stale-while-revalidate=120";
    private static final String DETAIL_CACHE = "public, max-age=60, stale-while-revalidate=300";

    private final PublicExploreQueryService service;

    public PublicExploreController(PublicExploreQueryService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<PublicExploreFacilityResponse>> list(HttpServletRequest request) {
        rejectQueryParameters(request);
        return ResponseEntity.ok()
                .header("Cache-Control", LIST_CACHE)
                .body(service.list().stream().map(PublicExploreFacilityMapper::from).toList());
    }

    @GetMapping("/{facilityId}")
    public ResponseEntity<PublicExploreFacilityResponse> detail(
            @PathVariable UUID facilityId, HttpServletRequest request) {
        rejectQueryParameters(request);
        return service.findById(facilityId)
                .map(PublicExploreFacilityMapper::from)
                .map(body -> ResponseEntity.ok().header("Cache-Control", DETAIL_CACHE).body(body))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static void rejectQueryParameters(HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "Public explore does not accept query parameters");
        }
    }
}
