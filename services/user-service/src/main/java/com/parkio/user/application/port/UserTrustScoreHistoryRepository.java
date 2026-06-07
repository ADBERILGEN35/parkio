package com.parkio.user.application.port;

import com.parkio.user.domain.UserTrustScoreHistory;

/** Persistence port for appending {@link UserTrustScoreHistory} entries. */
public interface UserTrustScoreHistoryRepository {

    UserTrustScoreHistory save(UserTrustScoreHistory history);
}
