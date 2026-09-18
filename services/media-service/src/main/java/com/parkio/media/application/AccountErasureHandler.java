package com.parkio.media.application;

import com.parkio.media.application.event.UserErasureRequestedEvent;
import com.parkio.media.application.port.MediaStoragePort;
import com.parkio.media.domain.MediaFile;
import com.parkio.media.infrastructure.client.AuthErasureAckClient;
import com.parkio.media.infrastructure.persistence.entity.ErasedUserTombstoneEntity;
import com.parkio.media.infrastructure.persistence.jpa.ErasedUserTombstoneJpaRepository;
import com.parkio.media.infrastructure.persistence.jpa.MediaFileJpaRepository;
import com.parkio.media.infrastructure.persistence.mapper.MediaPersistenceMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountErasureHandler {

    public static final String SERVICE_NAME = "media";

    private static final Logger log = LoggerFactory.getLogger(AccountErasureHandler.class);

    private final ErasedUserTombstoneJpaRepository tombstones;
    private final MediaFileJpaRepository mediaFiles;
    private final MediaStoragePort storage;
    private final JdbcTemplate jdbc;
    private final AuthErasureAckClient ackClient;
    private final Clock clock;

    public AccountErasureHandler(
            ErasedUserTombstoneJpaRepository tombstones,
            MediaFileJpaRepository mediaFiles,
            MediaStoragePort storage,
            JdbcTemplate jdbc,
            AuthErasureAckClient ackClient,
            Clock clock) {
        this.tombstones = tombstones;
        this.mediaFiles = mediaFiles;
        this.storage = storage;
        this.jdbc = jdbc;
        this.ackClient = ackClient;
        this.clock = clock;
    }

    @Transactional
    public void handle(UserErasureRequestedEvent event) {
        eraseLocal(event.authUserId());
        UUID ackEventId = UUID.nameUUIDFromBytes(
                (event.erasureRequestId() + ":" + SERVICE_NAME).getBytes(StandardCharsets.UTF_8));
        ackClient.acknowledge(ackEventId, event.erasureRequestId(), event.authUserId(), "SUCCESS");
        log.info("erasure completed requestId={} service={} status=SUCCESS",
                event.erasureRequestId(), SERVICE_NAME);
    }

    private void eraseLocal(UUID authUserId) {
        tombstones.save(new ErasedUserTombstoneEntity(authUserId, clock.instant()));
        for (var entity : mediaFiles.findByOwnerUserId(authUserId)) {
            MediaFile media = MediaPersistenceMapper.toDomain(entity);
            try {
                storage.delete(media.objectKey());
            } catch (RuntimeException ex) {
                if (!media.isDeleted()) {
                    throw ex;
                }
            }
            if (!media.isDeleted()) {
                media.softDelete(clock.instant());
                mediaFiles.save(MediaPersistenceMapper.toEntity(media));
            }
        }
        jdbc.update("DELETE FROM idempotency_records WHERE user_id = ?", authUserId);
    }
}
