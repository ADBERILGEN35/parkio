package com.parkio.auth.application;

import com.parkio.auth.application.port.RefreshTokenHasher;
import com.parkio.auth.application.port.RegistrationInviteRepository;
import com.parkio.auth.domain.RegistrationMode;
import com.parkio.auth.domain.exception.AuthErrorCode;
import com.parkio.auth.domain.exception.AuthException;
import com.parkio.auth.infrastructure.config.RegistrationProperties;
import java.time.Clock;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Enforces controlled registration policy (closed, invite-only, open) and consumes
 * registration invites in the same transaction as account creation.
 */
@Service
public class RegistrationGateService {

    static final Pattern PRIV001A_SYNTHETIC_EMAIL =
            Pattern.compile("^priv001a-[a-z0-9]{6,64}@priv001a\\.parkio\\.invalid$");

    private final RegistrationProperties properties;
    private final RegistrationInviteRepository registrationInvites;
    private final RefreshTokenHasher refreshTokenHasher;
    private final Clock clock;

    public RegistrationGateService(RegistrationProperties properties,
                                   RegistrationInviteRepository registrationInvites,
                                   RefreshTokenHasher refreshTokenHasher,
                                   Clock clock) {
        this.properties = properties;
        this.registrationInvites = registrationInvites;
        this.refreshTokenHasher = refreshTokenHasher;
        this.clock = clock;
    }

    public void assertRegistrationAllowed(String normalizedEmail, String inviteToken) {
        if (isPriv001aSyntheticBypass(normalizedEmail)) {
            return;
        }
        switch (properties.getMode()) {
            case CLOSED -> throw new AuthException(AuthErrorCode.REGISTRATION_CLOSED);
            case OPEN -> {
                // no invite required
            }
            case INVITE -> {
                if (!StringUtils.hasText(inviteToken)) {
                    throw new AuthException(AuthErrorCode.REGISTRATION_INVITE_REQUIRED);
                }
            }
        }
    }

    public void consumeInviteIfRequired(String normalizedEmail, String inviteToken) {
        if (isPriv001aSyntheticBypass(normalizedEmail)) {
            return;
        }
        if (properties.getMode() != RegistrationMode.INVITE) {
            return;
        }
        String tokenHash = refreshTokenHasher.hash(inviteToken);
        if (!registrationInvites.consumeIfValid(tokenHash, clock.instant())) {
            throw new AuthException(AuthErrorCode.REGISTRATION_INVITE_INVALID);
        }
    }

    private boolean isPriv001aSyntheticBypass(String normalizedEmail) {
        return properties.isPriv001aSyntheticBypass()
                && PRIV001A_SYNTHETIC_EMAIL.matcher(normalizedEmail).matches();
    }
}
