package com.parkio.analytics.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.parkio.analytics.application.event.UserErasureRequestedEvent;
import com.parkio.analytics.infrastructure.client.AuthErasureAckClient;
import com.parkio.analytics.infrastructure.persistence.jpa.ErasedUserTombstoneJpaRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class AccountErasureHandlerTest {

    @Test
    void handleIsIdempotent() {
        ErasedUserTombstoneJpaRepository tombstones = mock(ErasedUserTombstoneJpaRepository.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AuthErasureAckClient ack = mock(AuthErasureAckClient.class);
        when(tombstones.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(jdbc.update(anyString(), (Object[]) any())).thenReturn(1);
        AccountErasureHandler handler = new AccountErasureHandler(
                tombstones, jdbc, ack, Clock.fixed(Instant.parse("2026-08-14T00:00:00Z"), ZoneOffset.UTC));
        UserErasureRequestedEvent event = new UserErasureRequestedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Instant.parse("2026-08-14T00:00:00Z"));
        handler.handle(event);
        handler.handle(event);
        verify(ack, times(2)).acknowledge(any(), any(), any(), org.mockito.ArgumentMatchers.eq("SUCCESS"));
    }
}
