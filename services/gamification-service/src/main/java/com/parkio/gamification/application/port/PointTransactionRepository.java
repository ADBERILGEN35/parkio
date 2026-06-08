package com.parkio.gamification.application.port;

import com.parkio.gamification.domain.PointTransaction;
import java.util.List;
import java.util.UUID;

/** Persistence port for {@link PointTransaction} (the point ledger). */
public interface PointTransactionRepository {

    PointTransaction save(PointTransaction transaction);

    boolean existsByIdempotencyKey(String idempotencyKey);

    List<PointTransaction> findRecentByUserId(UUID userId, int limit);
}
