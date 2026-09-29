package com.parkio.auth.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.auth.application.admin.AdminApplicationService;
import com.parkio.auth.application.port.AuthUserRepository;
import com.parkio.auth.domain.AuthUser;
import com.parkio.auth.domain.AuthUserStatus;
import com.parkio.auth.domain.Role;
import com.parkio.auth.domain.RoleName;
import com.parkio.auth.domain.exception.AuthErrorCode;
import com.parkio.auth.domain.exception.AuthException;
import com.parkio.auth.infrastructure.persistence.jpa.RoleJpaRepository;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Transaction and row-lock behavior on the production database engine. */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdminEpochPostgresIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("parkio_auth_epoch_it")
                    .withUsername("parkio")
                    .withPassword("parkio");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("parkio.kafka.provision-topics", () -> "false");
        registry.add("parkio.kafka.relay.enabled", () -> "false");
        registry.add("parkio.lifecycle.retention.outbox-enabled", () -> "false");
        registry.add("parkio.lifecycle.retention.inbox-enabled", () -> "false");
        registry.add("management.tracing.enabled", () -> "false");
    }

    @Autowired private AdminApplicationService admin;
    @Autowired private AuthUserRepository users;
    @Autowired private RoleJpaRepository roles;
    @Autowired private TransactionTemplate transactions;

    @Test
    @Order(2)
    void committedRevokeAndRollbackKeepRoleAndEpochTogether() {
        UUID target = seed("target", RoleName.USER, RoleName.ADMIN);
        UUID actor = seed("actor", RoleName.SUPER_ADMIN);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            admin.revokeRole(actor, Set.of("SUPER_ADMIN"), target, RoleName.ADMIN, "synthetic rollback");
            throw new IllegalStateException("synthetic rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(users.findById(target).orElseThrow().hasRole(RoleName.ADMIN)).isTrue();
        assertThat(users.findById(target).orElseThrow().sessionEpoch()).isZero();

        admin.revokeRole(actor, Set.of("SUPER_ADMIN"), target, RoleName.ADMIN, "committed revoke");
        assertThat(users.findById(target).orElseThrow().hasRole(RoleName.ADMIN)).isFalse();
        assertThat(users.findById(target).orElseThrow().sessionEpoch()).isEqualTo(1L);
    }

    @Test
    @Order(3)
    void concurrentGlobalRevokesDoNotLoseAnEpochIncrement() throws Exception {
        UUID target = seed("sessions", RoleName.USER);
        UUID actor = seed("actor", RoleName.ADMIN);
        runTogether(
                () -> admin.revokeAllSessions(actor, Set.of("ADMIN"), target, "one"),
                () -> admin.revokeAllSessions(actor, Set.of("ADMIN"), target, "two"));
        assertThat(users.findById(target).orElseThrow().sessionEpoch()).isEqualTo(2L);
    }

    @Test
    @Order(1)
    void concurrentSuperAdminRemovalsCannotRemoveBoth() throws Exception {
        UUID first = seed("super-first", RoleName.SUPER_ADMIN);
        UUID second = seed("super-second", RoleName.SUPER_ADMIN);
        var outcomes = runTogether(
                () -> admin.revokeRole(first, Set.of("SUPER_ADMIN"), first, RoleName.SUPER_ADMIN, "one"),
                () -> admin.revokeRole(second, Set.of("SUPER_ADMIN"), second, RoleName.SUPER_ADMIN, "two"));
        assertThat(outcomes.stream().filter(error -> error == null).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(error -> error instanceof AuthException auth
                && auth.errorCode() == AuthErrorCode.LAST_SUPER_ADMIN).count()).isEqualTo(1);
        assertThat(users.countByRole(RoleName.SUPER_ADMIN)).isGreaterThanOrEqualTo(1);
    }

    private UUID seed(String label, RoleName... roleNames) {
        Set<Role> granted = java.util.Arrays.stream(roleNames)
                .map(name -> roles.findByName(name).orElseThrow())
                .map(entity -> new Role(entity.getId(), entity.getName()))
                .collect(java.util.stream.Collectors.toSet());
        Instant now = Instant.now();
        AuthUser user = new AuthUser(UUID.randomUUID(), label + "-" + UUID.randomUUID() + "@example.test",
                "synthetic-password-hash", AuthUserStatus.ACTIVE, null, granted, now, null);
        users.save(user);
        return user.id();
    }

    private static java.util.List<Throwable> runTogether(Runnable first, Runnable second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Throwable> a = executor.submit(() -> attempt(first, ready, start));
            Future<Throwable> b = executor.submit(() -> attempt(second, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return java.util.Arrays.asList(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
        }
    }

    private static Throwable attempt(Runnable action, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            action.run();
            return null;
        } catch (Throwable error) {
            return error;
        }
    }
}
