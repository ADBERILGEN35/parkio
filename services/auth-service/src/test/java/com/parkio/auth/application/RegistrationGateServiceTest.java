package com.parkio.auth.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.auth.application.port.RefreshTokenHasher;
import com.parkio.auth.application.port.RegistrationInviteRepository;
import com.parkio.auth.domain.RegistrationInvite;
import com.parkio.auth.domain.RegistrationMode;
import com.parkio.auth.domain.exception.AuthErrorCode;
import com.parkio.auth.domain.exception.AuthException;
import com.parkio.auth.infrastructure.config.RegistrationProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RegistrationGateServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-06T12:00:00Z");
    private static final String PRIV001_EMAIL = "priv001a-abcdef@priv001a.parkio.invalid";

    private FakeRegistrationInviteRepository registrationInvites;
    private FakeRefreshTokenHasher refreshTokenHasher;
    private RegistrationProperties properties;
    private RegistrationGateService gate;

    @BeforeEach
    void setUp() {
        registrationInvites = new FakeRegistrationInviteRepository();
        refreshTokenHasher = new FakeRefreshTokenHasher();
        properties = new RegistrationProperties();
        properties.setInviteTtl(Duration.ofDays(7));
        gate = new RegistrationGateService(
                properties, registrationInvites, refreshTokenHasher, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void closedModeRejectsRegistration() {
        properties.setMode(RegistrationMode.CLOSED);

        assertThatThrownBy(() -> gate.assertRegistrationAllowed("user@example.com", null))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).errorCode())
                .isEqualTo(AuthErrorCode.REGISTRATION_CLOSED);
    }

    @Test
    void openModeAllowsRegistrationWithoutInvite() {
        properties.setMode(RegistrationMode.OPEN);

        assertThatCode(() -> gate.assertRegistrationAllowed("user@example.com", null))
                .doesNotThrowAnyException();
        assertThatCode(() -> gate.consumeInviteIfRequired("user@example.com", null))
                .doesNotThrowAnyException();
    }

    @Test
    void inviteModeRequiresToken() {
        properties.setMode(RegistrationMode.INVITE);

        assertThatThrownBy(() -> gate.assertRegistrationAllowed("user@example.com", null))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).errorCode())
                .isEqualTo(AuthErrorCode.REGISTRATION_INVITE_REQUIRED);
    }

    @Test
    void inviteModeConsumesValidToken() {
        properties.setMode(RegistrationMode.INVITE);
        String rawToken = "invite-abc";
        seedInvite(rawToken);

        gate.assertRegistrationAllowed("user@example.com", rawToken);
        gate.consumeInviteIfRequired("user@example.com", rawToken);

        assertThatThrownBy(() -> gate.consumeInviteIfRequired("other@example.com", rawToken))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).errorCode())
                .isEqualTo(AuthErrorCode.REGISTRATION_INVITE_INVALID);
    }

    @Test
    void inviteModeRejectsInvalidTokenOnConsume() {
        properties.setMode(RegistrationMode.INVITE);

        assertThatThrownBy(() -> gate.consumeInviteIfRequired("user@example.com", "missing"))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).errorCode())
                .isEqualTo(AuthErrorCode.REGISTRATION_INVITE_INVALID);
    }

    @Test
    void priv001BypassSkipsClosedAndInviteChecks() {
        properties.setMode(RegistrationMode.CLOSED);
        properties.setPriv001aSyntheticBypass(true);

        assertThatCode(() -> gate.assertRegistrationAllowed(PRIV001_EMAIL, null))
                .doesNotThrowAnyException();
        assertThatCode(() -> gate.consumeInviteIfRequired(PRIV001_EMAIL, null))
                .doesNotThrowAnyException();
    }

    @Test
    void priv001BypassRequiresMatchingEmailPattern() {
        properties.setMode(RegistrationMode.CLOSED);
        properties.setPriv001aSyntheticBypass(true);

        assertThatThrownBy(() -> gate.assertRegistrationAllowed("priv001a-bad@example.com", null))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).errorCode())
                .isEqualTo(AuthErrorCode.REGISTRATION_CLOSED);
    }

    private void seedInvite(String rawToken) {
        registrationInvites.save(RegistrationInvite.issue(
                refreshTokenHasher.hash(rawToken), NOW.plus(Duration.ofDays(7)), NOW, "operator"));
    }

    private static final class FakeRegistrationInviteRepository implements RegistrationInviteRepository {
        private final Map<String, RegistrationInvite> byHash = new HashMap<>();

        @Override
        public RegistrationInvite save(RegistrationInvite invite) {
            byHash.put(invite.tokenHash(), invite);
            return invite;
        }

        @Override
        public boolean consumeIfValid(String tokenHash, Instant now) {
            RegistrationInvite invite = byHash.get(tokenHash);
            if (invite == null || !invite.isActive(now)) {
                return false;
            }
            invite.consume(now);
            return true;
        }
    }

    private static final class FakeRefreshTokenHasher implements RefreshTokenHasher {
        @Override
        public String hash(String rawToken) {
            return "rh:" + rawToken;
        }
    }
}
