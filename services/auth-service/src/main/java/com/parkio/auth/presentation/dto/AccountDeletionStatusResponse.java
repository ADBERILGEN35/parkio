package com.parkio.auth.presentation.dto;

import java.util.UUID;

public record AccountDeletionStatusResponse(UUID erasureRequestId, String status) {
}
