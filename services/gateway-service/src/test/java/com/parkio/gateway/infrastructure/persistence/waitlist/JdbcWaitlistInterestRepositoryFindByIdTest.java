package com.parkio.gateway.infrastructure.persistence.waitlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class JdbcWaitlistInterestRepositoryFindByIdTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    @SuppressWarnings("unchecked")
    void findByIdBindsUuidNotString() {
        UUID id = UUID.fromString("5b0c1d2e-3f40-4a5b-8c6d-7e8f90a1b2c3");
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any())).thenReturn(List.of());

        new JdbcWaitlistInterestRepository(jdbcTemplate).findById(id);

        ArgumentCaptor<Object> bound = ArgumentCaptor.forClass(Object.class);
        verify(jdbcTemplate).query(
                eq("SELECT * FROM waitlist_interest WHERE id = ?"),
                any(RowMapper.class),
                bound.capture());
        assertThat(bound.getValue()).isInstanceOf(UUID.class).isEqualTo(id);
    }
}
