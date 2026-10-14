package com.parkio.aivalidation.application.port;

import java.util.UUID;

/** Coordinates ai-validation writes with account erasure (N-07 / #361). */
public interface AiValidationOwnerFence {

    boolean admitWrite(UUID userId);

    void holdForErasure(UUID userId);
}
