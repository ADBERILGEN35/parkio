package com.parkio.auth.application.result;

import java.util.UUID;

public record AccountDeletionStatusView(UUID erasureRequestId, String status) {
}
