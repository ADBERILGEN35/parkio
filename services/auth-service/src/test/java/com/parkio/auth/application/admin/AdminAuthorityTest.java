package com.parkio.auth.application.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.auth.domain.exception.AuthErrorCode;
import com.parkio.auth.domain.exception.AuthException;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AdminAuthorityTest {

    @Test
    void parseRolesNormalizesAndSplits() {
        assertThat(AdminAuthority.parseRoles("ADMIN, moderator "))
                .containsExactlyInAnyOrder("ADMIN", "MODERATOR");
    }

    @Test
    void isAdminRecognizesAdminAndSuperAdmin() {
        assertThat(AdminAuthority.isAdmin(Set.of("ADMIN"))).isTrue();
        assertThat(AdminAuthority.isAdmin(Set.of("SUPER_ADMIN"))).isTrue();
        assertThat(AdminAuthority.isAdmin(Set.of("USER"))).isFalse();
    }

    @Test
    void isSuperAdminOnlyMatchesSuperAdmin() {
        assertThat(AdminAuthority.isSuperAdmin(Set.of("SUPER_ADMIN"))).isTrue();
        assertThat(AdminAuthority.isSuperAdmin(Set.of("ADMIN"))).isFalse();
    }

    @Test
    void requireAdminRejectsUserRole() {
        assertThatThrownBy(() -> AdminAuthority.requireAdmin(Set.of("USER")))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).errorCode())
                .isEqualTo(AuthErrorCode.FORBIDDEN);
    }

    @Test
    void requireSuperAdminRejectsAdminRole() {
        assertThatThrownBy(() -> AdminAuthority.requireSuperAdmin(Set.of("ADMIN")))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).errorCode())
                .isEqualTo(AuthErrorCode.FORBIDDEN);
    }
}
