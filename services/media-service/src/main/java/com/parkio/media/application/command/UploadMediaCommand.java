package com.parkio.media.application.command;

import java.util.UUID;

/**
 * Request to upload a media file. The controller has already extracted the raw
 * bytes and the client-declared content type; the original filename is
 * deliberately NOT carried — object keys are generated, never derived from
 * user-controlled input.
 */
public record UploadMediaCommand(UUID ownerUserId, String contentType, byte[] content) {
}
