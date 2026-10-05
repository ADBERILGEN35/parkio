package com.parkio.parking.infrastructure.izum;

import static com.parkio.parking.infrastructure.izum.IzumStatusObserver.METRIC;
import static com.parkio.parking.infrastructure.izum.IzumStatusObserver.classify;
import static com.parkio.parking.infrastructure.izum.IzumStatusObserver.sanitise;
import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.infrastructure.izum.IzumStatusObserver.Kind;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

// CL-F22, owner decision 2026-10-05: only Closed closes a car park; unknown statuses are observed, not aliased.
@ExtendWith(OutputCaptureExtension.class)
class IzumStatusObserverTest {
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final IzumStatusObserver observer = new IzumStatusObserver(registry);

    @Test void onlyOpenedAndClosedAreKnownInAnyCase() {
        assertThat(classify("Opened")).isEqualTo(Kind.OPENED);
        assertThat(classify(" OPENED ")).isEqualTo(Kind.OPENED);
        assertThat(classify("Closed")).isEqualTo(Kind.CLOSED);
        assertThat(classify(" closed ")).isEqualTo(Kind.CLOSED);
        for (String status : new String[] {"Kapalı", "Kapali", "Açık", "Open", "Close", "Maintenance", "0"}) {
            assertThat(classify(status)).as("status %s", status).isEqualTo(Kind.UNRECOGNISED);
        }
        for (String status : new String[] {null, "", "   "}) {
            assertThat(classify(status)).as("status [%s]", status).isEqualTo(Kind.MISSING);
        }
    }

    @Test void countsUnrecognisedAndMissingStatusesWithTheSourceAndKindLabelsOnly() {
        observer.observe(records("Opened", "Closed", "Kapalı", "Kapalı", "Maintenance", null, " "));

        assertThat(count("unrecognised")).isEqualTo(3.0);
        assertThat(count("missing")).isEqualTo(2.0);
        assertThat(registry.find(METRIC).counters()).hasSize(2).allSatisfy(counter ->
                assertThat(counter.getId().getTags()).extracting(Tag::getKey)
                        .containsExactlyInAnyOrder("source_key", "kind"));

        observer.observe(records("Kapalı"));

        assertThat(count("unrecognised")).isEqualTo(4.0);
    }

    @Test void knownStatusesAreNeitherCountedNorLogged(CapturedOutput output) {
        observer.observe(records("Opened", " closed ", "OPENED"));

        assertThat(count("unrecognised")).isZero();
        assertThat(count("missing")).isZero();
        assertThat(output.getOut()).doesNotContain("IZUM status");
    }

    @Test void logsEachValueOnlyTheFirstTimeItIsSeen(CapturedOutput output) {
        observer.observe(records("Kapalı", "Kapalı", null));
        observer.observe(records("Kapalı", null));

        assertThat(occurrences(output.getOut(), "IZUM status \"Kapalı\" is not recognised (2 records in this run)"))
                .isEqualTo(1);
        assertThat(occurrences(output.getOut(), "IZUM status is missing on 1 records in this run")).isEqualTo(1);
        assertThat(occurrences(output.getOut(), "IZUM status")).isEqualTo(2);
    }

    @Test void logsASanitisedAndCappedValue(CapturedOutput output) {
        String hostile = "Bad\nWARN forged line‮" + "x".repeat(60);

        observer.observe(records(hostile));

        String expected = "Bad?WARN forged line?" + "x".repeat(19) + "…";
        assertThat(sanitise(hostile)).isEqualTo(expected);
        assertThat(output.getOut()).contains("IZUM status \"" + expected + "\" is not recognised");
        assertThat(output.getOut()).doesNotContain("\nWARN forged line");
    }

    @Test void sanitiseReplacesUnsafeCharactersAndCountsCodePoints() {
        assertThat(sanitise(" Kapalı ")).isEqualTo("Kapalı");
        assertThat(sanitise("a\tb\nc\r\u0000d")).isEqualTo("a?b?c??d");
        assertThat(sanitise("x‮y z​w")).isEqualTo("x?y?z?w");
        assertThat(sanitise("x\uD800y")).isEqualTo("x?y");
        assertThat(sanitise("x".repeat(40))).isEqualTo("x".repeat(40));
        assertThat(sanitise("x".repeat(41))).isEqualTo("x".repeat(40) + "…");
        assertThat(sanitise("🚗".repeat(41))).isEqualTo("🚗".repeat(40) + "…");
    }

    @Test void remembersABoundedNumberOfValuesAndKeepsCountingTheRest(CapturedOutput output) {
        for (int i = 0; i < 40; i++) {
            observer.observe(records("Value" + i));
        }
        observer.observe(records("Value0", "Value39"));

        assertThat(occurrences(output.getOut(), "is not recognised")).isEqualTo(IzumStatusObserver.MAX_REMEMBERED_VALUES);
        assertThat(occurrences(output.getOut(), "distinct unknown statuses since start")).isEqualTo(1);
        assertThat(count("unrecognised")).isEqualTo(42.0);
    }

    @Test void aKnownValueDoesNotReportAnOverflowWhenTheBoundIsExactlyReached(CapturedOutput output) {
        for (int i = 0; i < IzumStatusObserver.MAX_REMEMBERED_VALUES; i++) {
            observer.observe(records("Value" + i));
        }
        observer.observe(records("Value0"));

        assertThat(occurrences(output.getOut(), "is not recognised")).isEqualTo(IzumStatusObserver.MAX_REMEMBERED_VALUES);
        assertThat(output.getOut()).doesNotContain("distinct unknown statuses since start");
    }

    private double count(String kind) {
        Counter counter = registry.find(METRIC).tag("kind", kind).counter();
        return counter == null ? 0 : counter.count();
    }

    private static int occurrences(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) count++;
        return count;
    }

    private static List<IzumParkingRecordDto> records(String... statuses) {
        List<IzumParkingRecordDto> records = new ArrayList<>();
        for (String status : statuses) {
            var total = new IzumParkingRecordDto.Total(4, 6);
            records.add(new IzumParkingRecordDto("ufid-1", "Synthetic Car Park", "IZUM", "OffStreet", status,
                    38.42, 27.14, new IzumParkingRecordDto.Occupancy(total, null), null, true, false,
                    "Synthetic Street 1", null, null, null, null, null, null));
        }
        return records;
    }
}
