package com.parkio.media.presentation;

import com.parkio.media.application.MediaApplicationService;
import com.parkio.media.application.result.MediaAccessUrl;
import com.parkio.media.presentation.dto.InternalAccessUrlRequest;
import com.parkio.media.presentation.dto.MediaAccessUrlResponse;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
}
