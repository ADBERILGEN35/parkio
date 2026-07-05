package com.parkio.gateway.infrastructure.web;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

public final class PathCanonicalization {

    private static final Pattern ENCODED_TRAVERSAL = Pattern.compile(
            "(?i)(%2e%2e|%252e%252e|\\.\\.%2f|%2f%2e%2e|%2e%2e%2f|%2f%2e%2e%2f)");
    private static final Pattern ENCODED_DOUBLE_SLASH = Pattern.compile("(?i)%2f%2f");

    private PathCanonicalization() {
    }

    public static boolean isUnsafeRawPath(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return false;
        }
        if (rawPath.indexOf('\\') >= 0) {
            return true;
        }
        if (rawPath.contains("//")) {
            return true;
        }
        if (ENCODED_TRAVERSAL.matcher(rawPath).find()) {
            return true;
        }
        if (ENCODED_DOUBLE_SLASH.matcher(rawPath).find()) {
            return true;
        }
        String decoded = decodePath(rawPath);
        if (decoded.indexOf('\\') >= 0) {
            return true;
        }
        if (decoded.contains("//")) {
            return true;
        }
        return containsDotDotSegment(decoded);
    }

    private static String decodePath(String rawPath) {
        String current = rawPath;
        for (int pass = 0; pass < 2; pass++) {
            try {
                String next = URLDecoder.decode(current, StandardCharsets.UTF_8);
                if (next.equals(current)) {
                    break;
                }
                current = next;
            } catch (IllegalArgumentException ex) {
                return current;
            }
        }
        return current;
    }

    private static boolean containsDotDotSegment(String path) {
        for (String segment : path.split("/", -1)) {
            if ("..".equals(segment)) {
                return true;
            }
        }
        return false;
    }
}