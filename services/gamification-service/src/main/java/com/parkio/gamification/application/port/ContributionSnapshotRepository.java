package com.parkio.gamification.application.port;

import com.parkio.gamification.domain.ContributionSnapshot;

/** Persistence port for {@link ContributionSnapshot} (append-only). */
public interface ContributionSnapshotRepository {

    ContributionSnapshot save(ContributionSnapshot snapshot);
}
