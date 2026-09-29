package com.parkio.parking.externalsource.izelman;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

@Component
public class IzelmanCsvReader {
    private static final Charset CP1254 = Charset.forName("windows-1254");

    public ParsedCsv read(byte[] bytes) {
        Decoded decoded = decode(bytes);
        String text = decoded.text().startsWith("\uFEFF") ? decoded.text().substring(1) : decoded.text();
        String firstLine = text.lines().findFirst().orElseThrow(() -> new IllegalArgumentException("CSV is empty"));
        char delimiter = count(firstLine, ';') > count(firstLine, ',') ? ';' : ',';
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setDelimiter(delimiter)
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .build();
        try (CSVParser parser = format.parse(new StringReader(text))) {
            List<String> headers = parser.getHeaderNames().stream().map(IzelmanCsvReader::normalizeHeader).toList();
            List<Map<String, String>> rows = new ArrayList<>();
            for (CSVRecord record : parser) {
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < parser.getHeaderNames().size(); i++) {
                    String value = record.isSet(i) ? record.get(i).trim() : "";
                    rejectFormula(value, record.getRecordNumber());
                    row.put(headers.get(i), value);
                }
                rows.add(Map.copyOf(row));
            }
            return new ParsedCsv(decoded.encoding(), delimiter, List.copyOf(headers), List.copyOf(rows),
                    sha256(String.join("|", headers).getBytes(StandardCharsets.UTF_8)));
        } catch (IOException | IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid İZELMAN CSV: " + ex.getMessage(), ex);
        }
    }

    private static Decoded decode(byte[] bytes) {
        int offset = bytes.length >= 3 && (bytes[0] & 0xff) == 0xef && (bytes[1] & 0xff) == 0xbb
                && (bytes[2] & 0xff) == 0xbf ? 3 : 0;
        try {
            return new Decoded(strictDecode(bytes, offset, StandardCharsets.UTF_8), offset == 3 ? "UTF-8-BOM" : "UTF-8");
        } catch (CharacterCodingException ignored) {
            try {
                return new Decoded(strictDecode(bytes, 0, CP1254), "windows-1254");
            } catch (CharacterCodingException ex) {
                throw new IllegalArgumentException("CSV encoding is neither UTF-8 nor windows-1254", ex);
            }
        }
    }

    private static String strictDecode(byte[] bytes, int offset, Charset charset) throws CharacterCodingException {
        return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
    }

    private static void rejectFormula(String value, long row) {
        if (!value.isEmpty() && "=+@-".indexOf(value.charAt(0)) >= 0) {
            throw new IllegalArgumentException("formula-like cell rejected at record " + row);
        }
    }

    private static String normalizeHeader(String value) {
        String header = value.startsWith("\uFEFF") ? value.substring(1) : value;
        return header.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static long count(String value, char needle) {
        return value.chars().filter(c -> c == needle).count();
    }

    public static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    public record ParsedCsv(
            String encoding, char delimiter, List<String> headers, List<Map<String, String>> rows,
            String schemaFingerprint) {}
    private record Decoded(String text, String encoding) {}
}
