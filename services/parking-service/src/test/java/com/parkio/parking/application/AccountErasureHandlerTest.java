package com.parkio.parking.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.parkio.parking.application.event.UserErasureRequestedEvent;
import com.parkio.parking.infrastructure.client.AuthErasureAckClient;
import com.parkio.parking.infrastructure.persistence.jpa.ErasedUserTombstoneJpaRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClientException;

class AccountErasureHandlerTest {

    private ErasedUserTombstoneJpaRepository tombstones;
    private JdbcTemplate jdbc;
    private AuthErasureAckClient ackClient;
    private AccountErasureHandler handler;

    @BeforeEach
    void setUp() {
        tombstones = mock(ErasedUserTombstoneJpaRepository.class);
        jdbc = mock(JdbcTemplate.class);
        ackClient = mock(AuthErasureAckClient.class);
        when(tombstones.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(jdbc.update(anyString(), (Object[]) any())).thenReturn(1);
        handler = new AccountErasureHandler(
                tombstones, jdbc, ackClient, Clock.fixed(Instant.parse("2026-08-14T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void handleIsIdempotentAndAcksSuccess() {
        UserErasureRequestedEvent event = event();
        handler.handle(event);
        handler.handle(event);
        verify(tombstones, times(2)).save(any());
        verify(ackClient, times(2)).acknowledge(any(), any(), any(), org.mockito.ArgumentMatchers.eq("SUCCESS"));
    }

    @Test
    void failedAckPropagatesForKafkaRetry() {
        doThrow(new RestClientException("down")).when(ackClient).acknowledge(any(), any(), any(), any());
        assertThatThrownBy(() -> handler.handle(event())).isInstanceOf(RestClientException.class);
        verify(tombstones).save(any());
    }

    private static UserErasureRequestedEvent event() {
        return new UserErasureRequestedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Instant.parse("2026-08-14T00:00:00Z"));
    }
}
