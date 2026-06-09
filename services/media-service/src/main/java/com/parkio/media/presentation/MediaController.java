package com.parkio.media.presentation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.media.application.MediaApplicationService;
import com.parkio.media.application.command.UploadMediaCommand;
import com.parkio.media.application.result.MediaUploadResult;
import com.parkio.media.domain.exception.MediaErrorCode;
import com.parkio.media.domain.exception.MediaException;
import com.parkio.media.infrastructure.idempotency.IdempotencyService;
import com.parkio.media.infrastructure.idempotency.IdempotentResponse;
import com.parkio.media.infrastructure.idempotency.RequestFingerprint;
import com.parkio.media.infrastructure.metrics.MediaMetrics;
import com.parkio.media.presentation.dto.MediaAccessUrlResponse;
import com.parkio.media.presentation.dto.MediaMetadataResponse;
import com.parkio.media.presentation.dto.UploadMediaResponse;
import com.parkio.media.presentation.dto.ValidationResultResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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
 * without a valid id fail closed. Roles come from the gateway-injected
 * {@code X-User-Roles} header: media reads are owner-only unless the caller holds
 * {@code MODERATOR} or {@code ADMIN}.
 */
@RestController
@RequestMapping("/api/v1/media")
public class MediaController {

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String ROLES_HEADER = "X-User-Roles";
    private static final Set<String> MODERATOR_ROLES = Set.of("MODERATOR", "ADMIN");

    private final MediaApplicationService mediaService;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    private final MediaMetrics mediaMetrics;

    public MediaController(MediaApplicationService mediaService,
                           IdempotencyService idempotencyService,
                           ObjectMapper objectMapper,
                           MediaMetrics mediaMetrics) {
        this.mediaService = mediaService;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
        this.mediaMetrics = mediaMetrics;
    }

    @PostMapping("/upload")
    public ResponseEntity<UploadMediaResponse> upload(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @RequestHeader(value = IdempotencyService.HEADER_NAME, required = false) String idempotencyKey,
            @RequestParam("file") MultipartFile file) {
        UUID ownerUserId = requireUserId(userId);
        byte[] content = readBytes(file);
        String path = "/api/v1/media/upload";
        String fingerprint = RequestFingerprint.sha256(objectMapper, uploadFingerprint(path, file, content));
        IdempotentResponse<UploadMediaResponse> response;
        try {
            response = idempotencyService.execute(
                    ownerUserId, "POST", path, idempotencyKey, fingerprint, UploadMediaResponse.class, () -> {
                        UploadMediaCommand command = new UploadMediaCommand(
                                ownerUserId, file.getContentType(), content);
                        MediaUploadResult result = mediaService.upload(command);
                        // Counted inside the supplier so idempotent replays are not re-counted.
                        mediaMetrics.uploadAccepted();
                        return IdempotentResponse.first(201, UploadMediaResponse.from(result));
                    });
        } catch (MediaException ex) {
            mediaMetrics.uploadRejected();
            throw ex;
        }
        return ResponseEntity.status(response.statusCode())
                .location(URI.create("/api/v1/media/" + response.body().mediaId()))
                .body(response.body());
    }

    /** Metadata — owner or moderator/admin only; others receive 404 (no id probing). */
    @GetMapping("/{mediaId}")
    public MediaMetadataResponse getMetadata(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @RequestHeader(value = ROLES_HEADER, required = false) String roles,
            @PathVariable("mediaId") UUID mediaId) {
        return MediaMetadataResponse.from(
                mediaService.getMetadata(mediaId, requireUserId(userId), hasModeratorRole(roles)));
    }

    /** Short-lived presigned GET URL — owner or moderator/admin only; never persisted. */
    @GetMapping("/{mediaId}/access-url")
    public MediaAccessUrlResponse getAccessUrl(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @RequestHeader(value = ROLES_HEADER, required = false) String roles,
            @PathVariable("mediaId") UUID mediaId) {
        return MediaAccessUrlResponse.from(
                mediaService.createAccessUrl(mediaId, requireUserId(userId), hasModeratorRole(roles)));
    }

    @DeleteMapping("/{mediaId}")
    public ResponseEntity<Void> delete(@RequestHeader(value = USER_ID_HEADER, required = false) String userId,
                                       @PathVariable("mediaId") UUID mediaId) {
        mediaService.delete(mediaId, requireUserId(userId));
        return ResponseEntity.noContent().build();
    }

    /** Validation internals — owner or moderator/admin only; others receive 404. */
    @GetMapping("/{mediaId}/validation-results")
    public List<ValidationResultResponse> getValidationResults(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @RequestHeader(value = ROLES_HEADER, required = false) String roles,
            @PathVariable("mediaId") UUID mediaId) {
        return mediaService.getValidationResults(mediaId, requireUserId(userId), hasModeratorRole(roles)).stream()
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

    /** True when the gateway-injected roles header contains MODERATOR or ADMIN. */
    private static boolean hasModeratorRole(String rolesHeader) {
        if (rolesHeader == null || rolesHeader.isBlank()) {
            return false;
        }
        Set<String> roles = Arrays.stream(rolesHeader.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
        return roles.stream().anyMatch(MODERATOR_ROLES::contains);
    }

    private static Map<String, Object> uploadFingerprint(
            String path, MultipartFile file, byte[] content) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("path", path);
        value.put("filename", file.getOriginalFilename());
        value.put("contentType", file.getContentType());
        value.put("fileSize", content.length);
        value.put("checksum", RequestFingerprint.sha256(content));
        return value;
    }
}
