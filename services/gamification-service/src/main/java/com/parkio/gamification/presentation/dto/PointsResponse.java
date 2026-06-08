package com.parkio.gamification.presentation.dto;

import com.parkio.gamification.domain.PointTransaction;
import com.parkio.gamification.domain.UserLevelProgress;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A user's point total plus their most recent ledger entries. */
public record PointsResponse(UUID userId, long totalPoints, List<Entry> recentTransactions) {

    public record Entry(
            String sourceType,
            String direction,
            long points,
            UUID relatedSpotId,
            Instant createdAt) {

        static Entry from(PointTransaction t) {
            return new Entry(t.sourceType().name(), t.direction().name(), t.points(),
                    t.relatedSpotId(), t.createdAt());
        }
    }

    public static PointsResponse of(UserLevelProgress progress, List<PointTransaction> transactions) {
        return new PointsResponse(progress.userId(), progress.totalPoints(),
                transactions.stream().map(Entry::from).toList());
    }
}
