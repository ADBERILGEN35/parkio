package com.parkio.auth.application.durable;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The canonical JSON of the recovery evidence contract: Python's
 * {@code json.dumps(value, separators=(",", ":"), sort_keys=True)} with its default
 * {@code ensure_ascii=True}, encoded as UTF-8 (docs/operations/recovery-evidence-contract.md
 * §4). Object keys are sorted by Unicode code point, and every character outside printable
 * ASCII is escaped exactly as Python escapes it: short escapes for quote, backslash, BS,
 * FF, LF, CR and TAB, otherwise a backslash-u escape with four lowercase hex digits per
 * UTF-16 unit (astral characters become a surrogate pair).
 *
 * <p>Supported values: {@code null}, {@link String}, {@link Boolean}, integral numbers
 * ({@code Byte}, {@code Short}, {@code Integer}, {@code Long}, {@link BigInteger}),
 * {@code Map<String, ?>} and {@link Collection}. Floating-point numbers are refused: the
 * evidence format has none, and Python and Java print them differently.
 */
public final class CanonicalJson {

    private static final Comparator<String> CODE_POINT_ORDER =
            (a, b) -> Arrays.compare(a.codePoints().toArray(), b.codePoints().toArray());

    private CanonicalJson() {
    }

    public static byte[] bytes(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value);
        return out.toString().getBytes(StandardCharsets.US_ASCII);
    }

    /** Parsed JSON as the plain values {@link #bytes} accepts (Python's {@code json.loads}). */
    public static Object plain(JsonNode node) {
        Objects.requireNonNull(node, "node");
        if (node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isIntegralNumber()) {
            return node.isBigInteger() ? node.bigIntegerValue() : node.longValue();
        }
        if (node.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                map.put(field.getKey(), plain(field.getValue()));
            }
            return map;
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>(node.size());
            node.forEach(item -> list.add(plain(item)));
            return list;
        }
        throw new IllegalArgumentException("not canonical JSON: " + node.getNodeType());
    }

    private static void write(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String text) {
            string(out, text);
        } else if (value instanceof Boolean flag) {
            out.append(flag ? "true" : "false");
        } else if (value instanceof Long || value instanceof Integer || value instanceof Short
                || value instanceof Byte || value instanceof BigInteger) {
            out.append(value);
        } else if (value instanceof Map<?, ?> map) {
            object(out, map);
        } else if (value instanceof Collection<?> items) {
            out.append('[');
            boolean first = true;
            for (Object item : items) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                write(out, item);
            }
            out.append(']');
        } else {
            throw new IllegalArgumentException("not canonical JSON: " + value.getClass().getName());
        }
    }

    private static void object(StringBuilder out, Map<?, ?> map) {
        List<String> keys = new ArrayList<>(map.size());
        for (Object key : map.keySet()) {
            if (!(key instanceof String text)) {
                throw new IllegalArgumentException("canonical JSON keys must be strings: " + key);
            }
            keys.add(text);
        }
        keys.sort(CODE_POINT_ORDER);
        out.append('{');
        boolean first = true;
        for (String key : keys) {
            if (!first) {
                out.append(',');
            }
            first = false;
            string(out, key);
            out.append(':');
            write(out, map.get(key));
        }
        out.append('}');
    }

    private static void string(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c >= 0x20 && c <= 0x7e) {
                        out.append(c);
                    } else {
                        // UTF-16 code units: astral characters come out as Python's surrogate pair.
                        out.append(String.format("\\u%04x", (int) c));
                    }
                }
            }
        }
        out.append('"');
    }
}
