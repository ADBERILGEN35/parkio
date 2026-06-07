package com.parkio.media.presentation;

import com.parkio.media.application.MediaApplicationService;
import com.parkio.media.application.command.UploadMediaCommand;
import com.parkio.media.application.result.MediaUploadResult;
import com.parkio.media.domain.exception.MediaErrorCode;
import com.parkio.media.domain.exception.MediaException;
import com.parkio.media.presentation.dto.MediaMetadataResponse;
import com.parkio.media.presentation.dto.UploadMediaResponse;
import com.parkio.media.presentation.dto.ValidationResultResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Media API. Translates HTTP into application commands and results into response
 * DTOs — JPA entities and domain objects never cross this boundary.
 *
 * <p>The authenticated user id is read from the {@code X-User-Id} header, which the
 * gateway strips from client input and re-injects after verifying the JWT. Requests
 * without a valid id fail closed.
 */
@RestController
@RequestMapping("/api/v1/media")
public class MediaController {

    private static final String USER_ID_HEADER = "X-User-Id";

    private final MediaApplicationService mediaService;

    public MediaController(MediaApplicationService mediaService) {
        this.mediaService = mediaService;
    }

    @PostMapping("/upload")
    public ResponseEntity<UploadMediaResponse> upload(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @RequestParam("file") MultipartFile file) {
        UUID ownerUserId = requireUserId(userId);
        UploadMediaCommand command = new UploadMediaCommand(ownerUserId, file.getContentType(), readBytes(file));
        MediaUploadResult result = mediaService.upload(command);
        return ResponseEntity
                .created(URI.create("/api/v1/media/" + result.mediaId()))
                .body(UploadMediaResponse.from(result));
    }

    @GetMapping("/{mediaId}")
    public MediaMetadataResponse getMetadata(@PathVariable("mediaId") UUID mediaId) {
        return MediaMetadataResponse.from(mediaService.getMetadata(mediaId));
    }

    @DeleteMapping("/{mediaId}")
    public ResponseEntity<Void> delete(@RequestHeader(value = USER_ID_HEADER, required = false) String userId,
                                       @PathVariable("mediaId") UUID mediaId) {
        mediaService.delete(mediaId, requireUserId(userId));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{mediaId}/validation-results")
    public List<ValidationResultResponse> getValidationResults(@PathVariable("mediaId") UUID mediaId) {
        return mediaService.getValidationResults(mediaId).stream()
                .map(ValidationResultResponse::from)
                .toList();
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read uploaded file", e);
        }
    }

    /** Resolves the authenticated user id from the header; fails closed if absent/invalid. */
    private static UUID requireUserId(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            throw new MediaException(MediaErrorCode.MISSING_USER_ID, "Missing authenticated user id.");
        }
        try {
            return UUID.fromString(headerValue.trim());
        } catch (IllegalArgumentException ex) {
            throw new MediaException(MediaErrorCode.MISSING_USER_ID, "Invalid authenticated user id.");
        }
    }
}
