package com.parkio.auth.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.auth.domain.exception.AuthErrorCode;
import com.parkio.auth.domain.exception.AuthException;
import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

    private final PasswordPolicy policy = new PasswordPolicy();

    @Test
    void acceptsSeventyTwoBytesAscii() {
        assertThatCode(() -> policy.validate("Aa1" + "x".repeat(69))).doesNotThrowAnyException();
    }

    @Test
    void acceptsSeventyTwoBytesOfMultibyteText() {
        assertThatCode(() -> policy.validate("Aa1" + "ş".repeat(34) + "x")).doesNotThrowAnyException();
    }

    @Test
    void rejectsSeventyThreeBytesAscii() {
        assertTooLong("Aa1" + "x".repeat(70));
    }

    @Test
    void rejectsSeventyThreeBytesOfMultibyteTextUnderTheCharacterLimit() {
        assertTooLong("Aa1" + "ş".repeat(35));
    }

    @Test
    void stillRejectsWeakPasswordsWithinTheByteBound() {
        assertThatThrownBy(() -> policy.validate("short1A"))
                .isInstanceOf(AuthException.class)
                .extracting(ex -> ((AuthException) ex).errorCode())
                .isEqualTo(AuthErrorCode.WEAK_PASSWORD);
    }

    private void assertTooLong(String password) {
        assertThatThrownBy(() -> policy.validate(password))
                .isInstanceOf(AuthException.class)
                .extracting(ex -> ((AuthException) ex).errorCode())
                .isEqualTo(AuthErrorCode.PASSWORD_TOO_LONG);
    }
}
