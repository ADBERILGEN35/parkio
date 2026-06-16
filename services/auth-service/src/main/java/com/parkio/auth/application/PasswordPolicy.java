package com.parkio.auth.application;

import com.parkio.auth.domain.exception.AuthErrorCode;
import com.parkio.auth.domain.exception.AuthException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Registration password policy. Keep this small and maintainable until zxcvbn is added. */
@Component
public class PasswordPolicy {

    public static final int MIN_LENGTH = 12;
    public static final int MAX_LENGTH = 100;

    private static final Pattern LOWERCASE = Pattern.compile("[a-z]");
    private static final Pattern UPPERCASE = Pattern.compile("[A-Z]");
    private static final Pattern DIGIT = Pattern.compile("\\d");
    private static final Set<String> DENY_LIST = Set.of(
            "password",
            "password1",
            "password12",
            "password123",
            "password1234",
            "password12345",
            "qwerty",
            "qwerty123",
            "qwerty1234",
            "admin",
            "admin123",
            "admin1234",
            "letmein",
            "welcome",
            "welcome123",
            "12345678",
            "123456789",
            "1234567890",
            "11111111",
            "00000000");

    public void validate(String rawPassword) {
        if (rawPassword == null
                || rawPassword.length() < MIN_LENGTH
                || rawPassword.length() > MAX_LENGTH
                || !LOWERCASE.matcher(rawPassword).find()
                || !UPPERCASE.matcher(rawPassword).find()
                || !DIGIT.matcher(rawPassword).find()
                || isDenied(rawPassword)) {
            throw new AuthException(AuthErrorCode.WEAK_PASSWORD);
        }
    }

    private static boolean isDenied(String rawPassword) {
        String normalized = rawPassword.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return DENY_LIST.contains(normalized);
    }
}
