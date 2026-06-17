package com.parkio.media.presentation;

import com.parkio.media.application.MediaApplicationService;
import com.parkio.media.application.result.MediaAccessUrl;
import com.parkio.media.presentation.dto.InternalAccessUrlRequest;
import com.parkio.media.presentation.dto.InternalMediaStatusResponse;
import com.parkio.media.presentation.dto.MediaAccessUrlResponse;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service media API. Not part of the public surface: the gateway only
 * routes {@code /api/v1/**}, so {@code /internal/**} is unreachable from outside,
 * and the {@link com.parkio.media.infrastructure.web.GatewayAuthFilter} still
 * requires the shared {@code X-Gateway-Auth} secret on these paths.
 *
 * <p>No ownership check is performed here — the calling service (parking-service)
 * has already authorized the requester against its own rules (spot visibility).
 * The optional request body carries the requester id and purpose for audit logging
 * only; it never influences authorization.
 */
@Hidden
@RestController
@RequestMapping("/internal/media")
public class InternalMediaController {

    private static final Logger log = LoggerFactory.getLogger(InternalMediaController.class);

    private final MediaApplicationService mediaService;

    public InternalMediaController(MediaApplicationService mediaService) {
        this.mediaService = mediaService;
    }

    @PostMapping("/{mediaId}/access-url")
    public MediaAccessUrlResponse createAccessUrl(@PathVariable("mediaId") UUID mediaId,
                                                  @RequestBody(required = false) InternalAccessUrlRequest request) {
        MediaAccessUrl accessUrl = mediaService.createAccessUrlForInternalCaller(mediaId);
        log.info("Issued internal access URL for media {} (requester={}, purpose={})",
                mediaId,
                request == null ? null : request.requesterUserId(),
                request == null ? null : request.purpose());
        return new MediaAccessUrlResponse(accessUrl.mediaId(), accessUrl.url(), accessUrl.expiresAt());
    }

    /**
     * Lifecycle status of a media object, used by parking-service to reject spot
     * creation that references non-{@code READY} media. Deleted/unknown media →
     * {@code 404 MEDIA_NOT_FOUND}.
     */
    @GetMapping("/{mediaId}/status")
    public InternalMediaStatusResponse getStatus(@PathVariable("mediaId") UUID mediaId) {
        return InternalMediaStatusResponse.of(mediaId, mediaService.getStatusForInternalCaller(mediaId));
    }
}
