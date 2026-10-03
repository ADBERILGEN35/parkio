package com.parkio.media.infrastructure.persistence;

import com.parkio.media.application.port.ObjectWriteLedger;
import com.parkio.media.infrastructure.config.MediaProperties;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link ObjectWriteLedger} on {@code media_object_writes} (V16). Every write is recorded against
 * the configured bucket; "now" methods run in a transaction of their own, so the ledger survives
 * the rollback of the upload that called them.
 */
@Component
public class MediaObjectWriteLedgerAdapter implements ObjectWriteLedger {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate ownTransaction;
    private final Clock clock;
    private final String bucket;

    public MediaObjectWriteLedgerAdapter(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, Clock clock,
                                         MediaProperties properties) {
        this.jdbc = jdbc;
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
        this.bucket = properties.getStorage().getBucket();
    }

    @Override
    public UUID recordPending(UUID ownerUserId, String objectKey) {
        UUID writeId = UUID.randomUUID();
        Timestamp now = Timestamp.from(clock.instant());
        ownTransaction.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO media_object_writes (id, owner_user_id, bucket_name, object_key, state, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'PENDING', ?, ?)
                """, writeId, ownerUserId, bucket, objectKey, now, now));
        return writeId;
    }

    @Override
    public void markApplied(UUID writeId) {
        Timestamp now = Timestamp.from(clock.instant());
        ownTransaction.executeWithoutResult(status -> jdbc.update(
                "UPDATE media_object_writes SET state = 'APPLIED', updated_at = ? WHERE id = ?", now, writeId));
    }

    @Override
    public void forgetNow(UUID writeId) {
        ownTransaction.executeWithoutResult(status -> forgetWithCaller(writeId));
    }

    @Override
    public void forgetWithCaller(UUID writeId) {
        jdbc.update("DELETE FROM media_object_writes WHERE id = ?", writeId);
    }
}
