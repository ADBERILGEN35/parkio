package com.parkio.auth.application;

import com.parkio.auth.application.port.RefreshTokenHasher;
import com.parkio.auth.application.port.RegistrationInviteRepository;
import com.parkio.auth.application.port.SecureTokenGenerator;
import com.parkio.auth.domain.RegistrationInvite;
import com.parkio.auth.infrastructure.config.RegistrationProperties;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Issues one-time registration invite tokens (plaintext returned only at creation). */
@Service
@Transactional
public class RegistrationInviteService {

    private final RegistrationInviteRepository registrationInvites;
    private final SecureTokenGenerator tokenGenerator;
    private final RefreshTokenHasher refreshTokenHasher;
    private final RegistrationProperties properties;
    private final Clock clock;

    public RegistrationInviteService(RegistrationInviteRepository registrationInvites,
                                       SecureTokenGenerator tokenGenerator,
                                       RefreshTokenHasher refreshTokenHasher,
                                       RegistrationProperties properties,
                                       Clock clock) {
        this.registrationInvites = registrationInvites;
        this.tokenGenerator = tokenGenerator;
        this.refreshTokenHasher = refreshTokenHasher;
        this.properties = properties;
        this.clock = clock;
    }

    public String createInvite(String createdBy) {
        String rawToken = tokenGenerator.generate();
        var now = clock.instant();
        RegistrationInvite invite = RegistrationInvite.issue(
                refreshTokenHasher.hash(rawToken),
                now.plus(properties.getInviteTtl()),
                now,
                createdBy);
        registrationInvites.save(invite);
        return rawToken;
    }
}
