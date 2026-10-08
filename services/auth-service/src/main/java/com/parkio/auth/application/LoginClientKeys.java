package com.parkio.auth.application;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/**
 * Client keys for login throttling (CL-F15): an IPv4 address as it is, an IPv6 address as its
 * {@link LoginThrottlePolicy#IPV6_CLIENT_PREFIX_BITS} network (so rotating addresses inside one /64 is
 * one client), and {@link LoginFailureTracker#UNKNOWN_CLIENT} for anything that is not an IP literal.
 * Never resolves a host name.
 */
public final class LoginClientKeys {

    /** Dotted-quad IPv4 without leading zeros (the form the gateway emits). */
    private static final Pattern IPV4 = Pattern.compile(
            "^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$");
    /**
     * IPv6 literal candidates: hex digits, colons and dots (embedded IPv4), starting with a hex digit or
     * a colon and containing a colon. For such a string {@link InetAddress#getByName} either parses an
     * address literal or throws; it never falls through to a name lookup.
     */
    private static final Pattern IPV6_CANDIDATE = Pattern.compile("^[0-9a-fA-F:][0-9a-fA-F:.]{1,44}$");

    private LoginClientKeys() {
    }

    public static String fromIpLiteral(String value) {
        if (value == null) {
            return LoginFailureTracker.UNKNOWN_CLIENT;
        }
        String candidate = value.trim();
        if (IPV4.matcher(candidate).matches()) {
            return candidate;
        }
        if (candidate.indexOf(':') < 0 || !IPV6_CANDIDATE.matcher(candidate).matches()) {
            return LoginFailureTracker.UNKNOWN_CLIENT;
        }
        byte[] address;
        try {
            address = InetAddress.getByName(candidate).getAddress();
        } catch (UnknownHostException | IllegalArgumentException e) {
            return LoginFailureTracker.UNKNOWN_CLIENT;
        }
        if (address.length == 4) {
            // An IPv4-mapped IPv6 address (::ffff:a.b.c.d) is the IPv4 client.
            return (address[0] & 0xff) + "." + (address[1] & 0xff) + "." + (address[2] & 0xff) + "." + (address[3] & 0xff);
        }
        return ipv6Network(address);
    }

    private static String ipv6Network(byte[] address) {
        int bits = LoginThrottlePolicy.IPV6_CLIENT_PREFIX_BITS;
        byte[] network = new byte[16];
        for (int i = 0; i < 16; i++) {
            int keep = Math.max(0, Math.min(8, bits - i * 8));
            network[i] = (byte) (address[i] & (0xff << (8 - keep)));
        }
        StringBuilder key = new StringBuilder();
        for (int group = 0; group < 8; group++) {
            if (group > 0) {
                key.append(':');
            }
            key.append(Integer.toHexString(((network[2 * group] & 0xff) << 8) | (network[2 * group + 1] & 0xff)));
        }
        return key.append('/').append(bits).toString();
    }
}
