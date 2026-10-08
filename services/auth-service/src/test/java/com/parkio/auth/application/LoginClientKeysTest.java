package com.parkio.auth.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;
import org.junit.jupiter.api.Test;

/** CL-F15 v2: IPv4 as it is, IPv6 by /64, anything else the shared unknown client; never a name lookup. */
class LoginClientKeysTest {

    @Test
    void ipv4AddressesAreTheirOwnKey() {
        assertThat(LoginClientKeys.fromIpLiteral("198.51.100.7")).isEqualTo("198.51.100.7");
        assertThat(LoginClientKeys.fromIpLiteral(" 203.0.113.255 ")).isEqualTo("203.0.113.255");
        assertThat(LoginClientKeys.fromIpLiteral("0.0.0.0")).isEqualTo("0.0.0.0");
    }

    @Test
    void ipv6AddressesInOneSlash64ShareOneKey() {
        String key = LoginClientKeys.fromIpLiteral("2001:db8:1:2::1");
        assertThat(key).isEqualTo("2001:db8:1:2:0:0:0:0/64");
        for (String sameNetwork : new String[] {"2001:DB8:1:2::1", "2001:db8:1:2:ffff:ffff:ffff:ffff",
                "2001:0db8:0001:0002:abcd:0000:0000:0042", "2001:db8:1:2:a:b:c:d"}) {
            assertThat(LoginClientKeys.fromIpLiteral(sameNetwork)).as(sameNetwork).isEqualTo(key);
        }
        assertThat(LoginClientKeys.fromIpLiteral("2001:db8:1:3::1")).isEqualTo("2001:db8:1:3:0:0:0:0/64");
        assertThat(LoginClientKeys.fromIpLiteral("::1")).isEqualTo("0:0:0:0:0:0:0:0/64");
    }

    @Test
    void anIpv4MappedIpv6AddressIsTheIpv4Client() {
        assertThat(LoginClientKeys.fromIpLiteral("::ffff:198.51.100.7")).isEqualTo("198.51.100.7");
    }

    @Test
    void anythingElseIsTheSharedUnknownClient() {
        for (String bad : new String[] {null, "", " ", "unknown", "localhost", "evil.example.com", "198.51.100.7, 203.0.113.9",
                "[2001:db8::1]", "fe80::1%eth0", "2001:db8::1::2", "a:b", "dead:beef:zz", "010.0.0.1", "1.2.3",
                "256.1.1.1", "1.2.3.4.5", ".::1", "a".repeat(46), "2001:db8::1/64"}) {
            assertThat(LoginClientKeys.fromIpLiteral(bad)).as(String.valueOf(bad)).isEqualTo(LoginFailureTracker.UNKNOWN_CLIENT);
        }
    }

    /** Random strings over the literal alphabet: always one of the three shapes, and fast (no lookups). */
    @Test
    void randomLiteralLikeInputYieldsOnlyTheThreeShapes() {
        Random random = new Random(20261008L);
        String alphabet = "0123456789abcdefABCDEF:.";
        long started = System.nanoTime();
        for (int i = 0; i < 20_000; i++) {
            int length = 1 + random.nextInt(45);
            StringBuilder value = new StringBuilder();
            for (int j = 0; j < length; j++) {
                value.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            String key = LoginClientKeys.fromIpLiteral(value.toString());
            assertThat(key.equals(LoginFailureTracker.UNKNOWN_CLIENT)
                    || key.matches("^(\\d{1,3}\\.){3}\\d{1,3}$")
                    || key.matches("^([0-9a-f]{1,4}:){7}[0-9a-f]{1,4}/64$")).as(value.toString()).isTrue();
        }
        assertThat(System.nanoTime() - started).isLessThan(10_000_000_000L);
    }
}
