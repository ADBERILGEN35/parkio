package com.parkio.gateway.application.waitlist;

import java.time.Instant;
import java.util.UUID;

/** Keyset position in the confirmed export: the last exported row's confirmation time and id. */
public record WaitlistExportCursor(Instant confirmedAt, UUID id) {
}
