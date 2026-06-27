package com.parkio.user.application.command;

import java.time.Instant;

public record SmartReturnReturnTimeCommand(Instant expectedReturnAt) {
}
