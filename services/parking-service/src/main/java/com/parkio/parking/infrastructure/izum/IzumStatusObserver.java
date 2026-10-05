package com.parkio.parking.infrastructure.izum;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Makes IZUM statuses outside the known vocabulary visible to operators (CL-F22, owner decision
 * 2026-10-05). Only {@code Closed} (any case) closes a car park ({@link IzumNormalizer#isClosed}).
 * The repository's recorded IZUM fixture holds only {@code Opened} (12 of 12 records): a sample,
 * not live monitoring. The provider's vocabulary is not verified, so any other value, or none, keeps
 * today's behaviour: the car park stays open. No aliases are assumed. This class observes and never
 * changes what is published:
 * <ul>
 *   <li>{@value #METRIC} counts each sync run's valid records whose status is unrecognised or
 *       missing. Its labels are {@code source_key} and {@code kind} only, never the value.</li>
 *   <li>The first time a value is seen after start, one WARN line names it, sanitised: ISO control
 *       characters and the Unicode categories format (Cf), line and paragraph separator (Zl, Zp),
 *       surrogate (Cs), private use (Co) and unassigned (Cn) become {@code ?}, and it is capped at
 *       {@value #MAX_LOGGED_LENGTH} code points. At most {@value #MAX_REMEMBERED_VALUES} values are
 *       remembered; later new values are counted, and one line says they are no longer logged.</li>
 * </ul>
 */
@Component
public class IzumStatusObserver {
    static final String METRIC = "parkio.municipal.izum.unknown_status";
    static final int MAX_LOGGED_LENGTH = 40;
    static final int MAX_REMEMBERED_VALUES = 32;
    private static final String MISSING_KEY = "\u0000missing";
    private static final Logger log = LoggerFactory.getLogger(IzumStatusObserver.class);

    enum Kind { OPENED, CLOSED, UNRECOGNISED, MISSING }

    private final Counter unrecognised;
    private final Counter missing;
    private final Set<String> seen = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean overflowLogged = new AtomicBoolean();

    public IzumStatusObserver(MeterRegistry registry) {
        this.unrecognised = counter(registry, "unrecognised");
        this.missing = counter(registry, "missing");
    }

    private static Counter counter(MeterRegistry registry, String kind) {
        return Counter.builder(METRIC)
                .description("IZUM records per sync run whose status is neither Opened nor Closed (any case);"
                        + " they stay open")
                .tag("source_key", IzumMunicipalParkingAdapter.SOURCE_KEY)
                .tag("kind", kind)
                .register(registry);
    }

    static Kind classify(String status) {
        if (status == null || status.isBlank()) return Kind.MISSING;
        if (IzumNormalizer.isClosed(status)) return Kind.CLOSED;
        if ("opened".equalsIgnoreCase(status.trim())) return Kind.OPENED;
        return Kind.UNRECOGNISED;
    }

    /** Counts and reports the unknown statuses among the valid records of one sync run. */
    public void observe(List<IzumParkingRecordDto> records) {
        Map<String, Integer> unrecognisedValues = new LinkedHashMap<>();
        int missingCount = 0;
        for (IzumParkingRecordDto record : records) {
            switch (classify(record.status())) {
                case UNRECOGNISED -> unrecognisedValues.merge(sanitise(record.status()), 1, Integer::sum);
                case MISSING -> missingCount++;
                default -> { }
            }
        }
        int unrecognisedCount = unrecognisedValues.values().stream().mapToInt(Integer::intValue).sum();
        if (unrecognisedCount > 0) unrecognised.increment(unrecognisedCount);
        if (missingCount > 0) missing.increment(missingCount);
        unrecognisedValues.forEach((value, count) -> {
            if (firstSighting(value)) {
                log.warn("IZUM status \"{}\" is not recognised ({} records in this run). Only Closed (any case)"
                        + " closes an IZUM car park, so these stay open, as before; the provider's status"
                        + " vocabulary is not verified. Later runs count it in {} without logging it again.",
                        value, count, METRIC);
            }
        });
        if (missingCount > 0 && firstSighting(MISSING_KEY)) {
            log.warn("IZUM status is missing on {} records in this run. These stay open, as before. Later runs"
                    + " count them in {} without logging it again.", missingCount, METRIC);
        }
    }

    private boolean firstSighting(String key) {
        if (seen.contains(key)) return false;
        if (seen.size() >= MAX_REMEMBERED_VALUES) {
            if (overflowLogged.compareAndSet(false, true)) {
                log.warn("IZUM: more than {} distinct unknown statuses since start; further new values are"
                        + " counted in {} but not logged.", MAX_REMEMBERED_VALUES, METRIC);
            }
            return false;
        }
        return seen.add(key);
    }

    /** The trimmed value with unsafe characters replaced and its length capped, for one log line. */
    static String sanitise(String status) {
        String value = status.trim();
        StringBuilder out = new StringBuilder();
        int length = 0;
        for (int i = 0; i < value.length(); ) {
            int codePoint = value.codePointAt(i);
            i += Character.charCount(codePoint);
            if (length == MAX_LOGGED_LENGTH) {
                out.append('…');
                break;
            }
            out.appendCodePoint(unsafe(codePoint) ? '?' : codePoint);
            length++;
        }
        return out.toString();
    }

    private static boolean unsafe(int codePoint) {
        int type = Character.getType(codePoint);
        return Character.isISOControl(codePoint)
                || type == Character.FORMAT
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR
                || type == Character.SURROGATE
                || type == Character.PRIVATE_USE
                || type == Character.UNASSIGNED;
    }
}
