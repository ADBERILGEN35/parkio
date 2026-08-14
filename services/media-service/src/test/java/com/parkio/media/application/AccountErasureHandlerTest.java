package com.parkio.media.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.parkio.media.application.event.UserErasureRequestedEvent;
import com.parkio.media.application.port.MediaStoragePort;
import com.parkio.media.domain.MediaFile;
import com.parkio.media.domain.MediaStatus;
import com.parkio.media.infrastructure.client.AuthErasureAckClient;
import com.parkio.media.infrastructure.persistence.jpa.ErasedUserTombstoneJpaRepository;
import com.parkio.media.infrastructure.persistence.jpa.MediaFileJpaRepository;
import com.parkio.media.infrastructure.persistence.mapper.MediaPersistenceMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class AccountErasureHandlerTest {

    private ErasedUserTombstoneJpaRepository tombstones;
    private MediaFileJpaRepository mediaFiles;
    private MediaStoragePort storage;
    private JdbcTemplate jdbc;
    private AuthErasureAckClient ackClient;
    private AccountErasureHandler handler;
    private UUID owner;
    private MediaFile ready;

    @BeforeEach
    void setUp() {
        tombstones = mock(ErasedUserTombstoneJpaRepository.class);
        mediaFiles = mock(MediaFileJpaRepository.class);
        storage = mock(MediaStoragePort.class);
        jdbc = mock(JdbcTemplate.class);
        ackClient = mock(AuthErasureAckClient.class);
        when(tombstones.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(jdbc.update(anyString(), (Object[]) any())).thenReturn(1);
        owner = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-14T00:00:00Z");
        ready = MediaFile.create(owner, "b", "k/obj", "image/jpeg", 12, "abc", null, null, now);
        ready.markReady(now);
        when(mediaFiles.findByOwnerUserId(owner)).thenReturn(List.of(MediaPersistenceMapper.toEntity(ready)));
        handler = new AccountErasureHandler(
                tombstones, mediaFiles, storage, jdbc, ackClient, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void deletesStorageThenSoftDeletesAndIsIdempotent() {
        UserErasureRequestedEvent event = new UserErasureRequestedEvent(
                UUID.randomUUID(), UUID.randomUUID(), owner, Instant.parse("2026-08-14T00:00:00Z"));
        handler.handle(event);
        MediaFile deleted = ready;
        deleted.softDelete(Instant.parse("2026-08-14T00:00:00Z"));
        when(mediaFiles.findByOwnerUserId(owner)).thenReturn(List.of(MediaPersistenceMapper.toEntity(deleted)));
        handler.handle(event);
        verify(storage, times(2)).delete("k/obj");
        verify(ackClient, times(2)).acknowledge(any(), any(), any(), org.mockito.ArgumentMatchers.eq("SUCCESS"));
        verify(mediaFiles).save(any());
    }
}
