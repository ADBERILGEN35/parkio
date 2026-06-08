package com.parkio.moderation.application.port;

import com.parkio.moderation.domain.ModerationReason;
import com.parkio.moderation.domain.ModerationTargetType;
import com.parkio.moderation.domain.UserReport;
import java.util.List;
import java.util.UUID;

/** Persistence port for {@link UserReport}. */
public interface UserReportRepository {

    UserReport save(UserReport report);

    boolean existsByReporterAndTargetAndReason(UUID reporterUserId, ModerationTargetType targetType,
                                               UUID targetId, ModerationReason reason);

    List<UserReport> findByReporterUserId(UUID reporterUserId);
}
