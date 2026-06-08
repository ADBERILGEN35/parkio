package com.parkio.moderation.application.port;

import com.parkio.moderation.domain.UserViolation;

/** Persistence port for {@link UserViolation} (append-only). */
public interface UserViolationRepository {

    UserViolation save(UserViolation violation);
}
