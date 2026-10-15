package com.parkio.parking.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.parkio.parking.application.event.UserErasureRequestedEvent;
import com.parkio.parking.application.port.ErasureAckOutbox;
import com.parkio.parking.application.port.ParkingOwnerFence;
import com.parkio.parking.infrastructure.persistence.jpa.ErasedUserTombstoneJpaRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** CL-N12 / F-10R: parking free-text must be cleared on erasure. */
class AccountErasureFreeTextRetentionTest {

    private static final Instant NOW = Instant.parse("2026-10-15T09:00:00Z");

    private JdbcTemplate jdbc;
    private AccountErasureHandler handler;

    @BeforeEach
    void setUp() {
        ErasedUserTombstoneJpaRepository tombstones = mock(ErasedUserTombstoneJpaRepository.class);
        when(tombstones.save(any())).thenAnswer(inv -> inv.getArgument(0));
        jdbc = mock(JdbcTemplate.class);
        handler = new AccountErasureHandler(
                tombstones,
                jdbc,
                mock(ErasureAckOutbox.class),
                mock(ParkingOwnerFence.class),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void erasureClearsDescriptionAndAddressText() {
        UUID userId = UUID.randomUUID();
        handler.handle(new UserErasureRequestedEvent(UUID.randomUUID(), UUID.randomUUID(), userId, NOW));

        verify(jdbc).update(
                contains("description = NULL, address_text = NULL"),
                eq(AccountErasureHandler.ERASED_USER_SENTINEL),
                eq(userId));
    }
}
