package com.parkio.gateway.application.waitlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class WaitlistFullNameTest {

    @Test
    void acceptsUnicodeSpacesApostropheHyphenAndPeriod() {
        assertThat(WaitlistFullName.requireOrOptional("  Ayşe Yılmaz  ", true)).isEqualTo("Ayşe Yılmaz");
        assertThat(WaitlistFullName.requireOrOptional("Jean-Luc O'Connor", true)).isEqualTo("Jean-Luc O'Connor");
        assertThat(WaitlistFullName.requireOrOptional("Mary Anne", false)).isEqualTo("Mary Anne");
        assertThat(WaitlistFullName.requireOrOptional("李雷", true)).isEqualTo("李雷");
        assertThat(WaitlistFullName.requireOrOptional("J. K. Rowling", true)).isEqualTo("J. K. Rowling");
        assertThat(WaitlistFullName.requireOrOptional("O’Neill", true)).isEqualTo("O’Neill");
    }

    @Test
    void rejectsBlankWhenRequiredAndAllowsAbsentWhenOptional() {
        assertThat(WaitlistFullName.requireOrOptional(null, false)).isNull();
        assertThat(WaitlistFullName.requireOrOptional("   ", false)).isNull();
        assertThatThrownBy(() -> WaitlistFullName.requireOrOptional(null, true))
                .isInstanceOf(WaitlistFullNameException.class)
                .extracting(ex -> ((WaitlistFullNameException) ex).code())
                .isEqualTo("WAITLIST_FULL_NAME_REQUIRED");
        assertThatThrownBy(() -> WaitlistFullName.requireOrOptional("  ", true))
                .isInstanceOf(WaitlistFullNameException.class)
                .extracting(ex -> ((WaitlistFullNameException) ex).code())
                .isEqualTo("WAITLIST_FULL_NAME_REQUIRED");
    }

    @Test
    void rejectsInvalidCharactersAndOverlong() {
        assertThatThrownBy(() -> WaitlistFullName.requireOrOptional("Name@Slack", false))
                .isInstanceOf(WaitlistFullNameException.class)
                .extracting(ex -> ((WaitlistFullNameException) ex).code())
                .isEqualTo("WAITLIST_FULL_NAME_INVALID");
        assertThatThrownBy(() -> WaitlistFullName.requireOrOptional("a".repeat(101), false))
                .isInstanceOf(WaitlistFullNameException.class)
                .extracting(ex -> ((WaitlistFullNameException) ex).code())
                .isEqualTo("WAITLIST_FULL_NAME_INVALID");
        assertThatThrownBy(() -> WaitlistFullName.requireOrOptional("12345", false))
                .isInstanceOf(WaitlistFullNameException.class);
    }
}
