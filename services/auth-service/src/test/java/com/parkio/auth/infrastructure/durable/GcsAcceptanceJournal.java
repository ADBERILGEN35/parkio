package com.parkio.auth.infrastructure.durable;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Append-only record of one #483 harness run, written while the run happens: every write before it
 * is sent (with the retain-until it asks for), every generation the provider acknowledged, every
 * requested retention change, every case result and the stop reason. Each line is flushed, so if
 * the process dies before {@code result.json} is written the file still shows what may exist in
 * the bucket and the latest retain-until any request asked for. Values pass through
 * {@link GcsAcceptanceLedger#sanitize(String)}; nothing secret is recorded.
 */
final class GcsAcceptanceJournal implements Closeable {

    static final String FILE = "journal.jsonl";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final BufferedWriter out;
    private final Clock clock;
    private boolean closed;

    private GcsAcceptanceJournal(BufferedWriter out, Clock clock) {
        this.out = out;
        this.clock = clock;
    }

    /** Creates {@code journal.jsonl} in {@code directory}; an existing journal is never appended to. */
    static GcsAcceptanceJournal create(Path directory, Clock clock) throws IOException {
        BufferedWriter writer = Files.newBufferedWriter(directory.resolve(FILE), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return new GcsAcceptanceJournal(writer, Objects.requireNonNull(clock, "clock"));
    }

    /** One event; string values are sanitized. A failed append stops the run rather than losing a record. */
    synchronized void append(String event, Map<String, Object> fields) {
        if (closed) {
            return;
        }
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("at", clock.instant().toString());
        line.put("event", event);
        fields.forEach((key, value) -> line.put(key, value instanceof String text
                ? GcsAcceptanceLedger.sanitize(text) : value));
        try {
            out.write(JSON.writeValueAsString(line));
            out.write('\n');
            out.flush();
        } catch (IOException ex) {
            throw new UncheckedIOException("journal append failed", ex);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (!closed) {
            closed = true;
            out.close();
        }
    }
}
