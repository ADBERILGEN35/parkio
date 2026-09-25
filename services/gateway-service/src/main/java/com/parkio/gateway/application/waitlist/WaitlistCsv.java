package com.parkio.gateway.application.waitlist;

/**
 * CSV cell encoding with formula-injection resistance for spreadsheet clients.
 */
public final class WaitlistCsv {

    private WaitlistCsv() {
    }

    public static String cell(String value) {
        if (value == null || value.isEmpty()) {
            return "\"\"";
        }
        String safe = value;
        char first = safe.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@'
                || first == '\t' || first == '\r') {
            safe = "'" + safe;
        }
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }
}
