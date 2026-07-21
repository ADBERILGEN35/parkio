package com.parkio.media.application.command;

import com.parkio.media.domain.ClaimedRegion;
import java.util.UUID;

/** Owner request to set/replace the claimed parking region on an existing media file. */
public record SetClaimedRegionCommand(UUID mediaId, UUID ownerUserId, ClaimedRegion claimedRegion) {
}
