package com.parkio.user.presentation;

import com.parkio.user.application.RecentParkingApplicationService;
import com.parkio.user.domain.exception.UserErrorCode;
import com.parkio.user.domain.exception.UserException;
import com.parkio.user.domain.place.RecentParkingTargetKind;
import com.parkio.user.presentation.dto.RecentParkingListResponse;
import com.parkio.user.presentation.dto.RecentParkingResponse;
import com.parkio.user.presentation.dto.RecordRecentParkingRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authenticated recently used parking API (WP-SPA-07).
 * Gated by {@code parkio.spa.recents.enabled}.
 */
@RestController
@RequestMapping("/api/v1/places/recents/parking")
public class RecentParkingController {

    private static final String USER_ID_HEADER = "X-User-Id";

    private final RecentParkingApplicationService recents;
    private final boolean recentsEnabled;

    public RecentParkingController(
            RecentParkingApplicationService recents,
            @Value("${parkio.spa.recents.enabled:false}") boolean recentsEnabled) {
        this.recents = recents;
        this.recentsEnabled = recentsEnabled;
    }

    @GetMapping
    public RecentParkingListResponse list(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId) {
        requireEnabled();
        return new RecentParkingListResponse(
                recents.list(requireUserId(userId)).stream()
                        .map(RecentParkingResponse::from)
                        .toList());
    }

    @PostMapping
    public RecentParkingResponse record(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @Valid @RequestBody RecordRecentParkingRequest request) {
        requireEnabled();
        RecentParkingTargetKind kind =
                request.targetKind() == null ? RecentParkingTargetKind.MUNICIPAL_FACILITY : request.targetKind();
        return RecentParkingResponse.from(recents.record(requireUserId(userId), kind, request.targetId()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @PathVariable("id") UUID id) {
        requireEnabled();
        recents.delete(requireUserId(userId), id);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearAll(@RequestHeader(value = USER_ID_HEADER, required = false) String userId) {
        requireEnabled();
        recents.clearAll(requireUserId(userId));
    }

    private void requireEnabled() {
        if (!recentsEnabled) {
            throw new UserException(UserErrorCode.RECENTS_DISABLED);
        }
    }

    private static UUID requireUserId(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            throw new UserException(UserErrorCode.MISSING_USER_ID);
        }
        try {
            return UUID.fromString(headerValue.trim());
        } catch (IllegalArgumentException ex) {
            throw new UserException(UserErrorCode.MISSING_USER_ID);
        }
    }
}
