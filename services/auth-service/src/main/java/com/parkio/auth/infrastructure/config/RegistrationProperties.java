package com.parkio.auth.infrastructure.config;

import com.parkio.auth.domain.RegistrationMode;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Binds {@code parkio.registration.*} for controlled registration and invite tokens. */
@Validated
@ConfigurationProperties(prefix = "parkio.registration")
public class RegistrationProperties {

    private RegistrationMode mode = RegistrationMode.CLOSED;
    private boolean priv001aSyntheticBypass;
    private Duration inviteTtl = Duration.ofDays(7);
    private boolean inviteCreationEnabled;
    private String inviteOperatorToken = "";

    public RegistrationMode getMode() {
        return mode;
    }

    public void setMode(RegistrationMode mode) {
        this.mode = mode;
    }

    public boolean isPriv001aSyntheticBypass() {
        return priv001aSyntheticBypass;
    }

    public void setPriv001aSyntheticBypass(boolean priv001aSyntheticBypass) {
        this.priv001aSyntheticBypass = priv001aSyntheticBypass;
    }

    public Duration getInviteTtl() {
        return inviteTtl;
    }

    public void setInviteTtl(Duration inviteTtl) {
        this.inviteTtl = inviteTtl;
    }

    public boolean isInviteCreationEnabled() {
        return inviteCreationEnabled;
    }

    public void setInviteCreationEnabled(boolean inviteCreationEnabled) {
        this.inviteCreationEnabled = inviteCreationEnabled;
    }

    public String getInviteOperatorToken() {
        return inviteOperatorToken;
    }

    public void setInviteOperatorToken(String inviteOperatorToken) {
        this.inviteOperatorToken = inviteOperatorToken == null ? "" : inviteOperatorToken;
    }
}
