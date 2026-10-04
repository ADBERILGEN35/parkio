package com.parkio.gateway.application.waitlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The export counts first and then pulls keyset pages of at most {@code EXPORT_PAGE_SIZE} rows,
 * continuing after the last row of each page and stopping at the row limit.
 */
class WaitlistExportStreamingTest {

    private static final Instant FROM = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-01T00:00:00Z");

    @Test
    void pagesAreBoundedAndTheExportStopsAtTheLimit() {
        FakeRepository repository = new FakeRepository(3_000);
        WaitlistProperties properties = new WaitlistProperties();
        properties.getExport().setMaxRows(2_500);

        WaitlistExport export = service(repository.mock, properties).export(FROM, TO).block();
        List<List<WaitlistExportRow>> pages = export.pages().collectList().block();

        assertThat(export.matchingRows()).isEqualTo(3_000);
        assertThat(export.rowLimit()).isEqualTo(2_500);
        assertThat(export.truncated()).isTrue();
        assertThat(pages).extracting(List::size).containsExactly(1_000, 1_000, 500);
        assertThat(repository.requestedLimits).containsExactly(1_000, 1_000, 500);
        assertThat(repository.cursors.get(0)).isNull();
        assertThat(repository.cursors.get(1)).isEqualTo(cursorOf(pages.get(0)));
        assertThat(repository.cursors.get(2)).isEqualTo(cursorOf(pages.get(1)));
        assertThat(repository.windows).allSatisfy(window -> assertThat(window).containsExactly(FROM, TO));
    }

    @Test
    void anExportWithinTheLimitIsNotTruncatedAndEndsAtTheLastPage() {
        FakeRepository repository = new FakeRepository(1_200);

        WaitlistExport export = service(repository.mock, new WaitlistProperties()).export(null, null).block();
        List<List<WaitlistExportRow>> pages = export.pages().collectList().block();

        assertThat(export.truncated()).isFalse();
        assertThat(export.rowLimit()).isEqualTo(50_000);
        assertThat(pages).extracting(List::size).containsExactly(1_000, 200);
    }

    @Test
    void anExportOfExactlyTheLimitIsComplete() {
        FakeRepository repository = new FakeRepository(2_000);
        WaitlistProperties properties = new WaitlistProperties();
        properties.getExport().setMaxRows(2_000);

        WaitlistExport export = service(repository.mock, properties).export(null, null).block();
        List<List<WaitlistExportRow>> pages = export.pages().collectList().block();

        assertThat(export.truncated()).isFalse();
        assertThat(pages).extracting(List::size).containsExactly(1_000, 1_000);
    }

    private static WaitlistExportCursor cursorOf(List<WaitlistExportRow> page) {
        WaitlistExportRow last = page.get(page.size() - 1);
        return new WaitlistExportCursor(last.confirmedAt(), last.id());
    }

    private static WaitlistApplicationService service(WaitlistInterestRepository repository,
                                                      WaitlistProperties properties) {
        return new WaitlistApplicationService(repository, null, null, null, properties, null, null, Clock.systemUTC());
    }

    /** Serves {@code total} rows in order and records what each page query asked for. */
    private static final class FakeRepository {

        private final List<WaitlistExportRow> rows = new ArrayList<>();
        private final List<Integer> requestedLimits = new ArrayList<>();
        private final List<WaitlistExportCursor> cursors = new ArrayList<>();
        private final List<List<Instant>> windows = new ArrayList<>();
        private final WaitlistInterestRepository mock = mock(WaitlistInterestRepository.class);

        FakeRepository(int total) {
            for (int i = 0; i < total; i++) {
                rows.add(new WaitlistExportRow(new UUID(0, i), FROM.plusSeconds(i), "user" + i + "@parkio.dev",
                        null, null, null, "parkio.dev-landing", FROM, FROM));
            }
            when(mock.countConfirmedForExport(any(), any())).thenReturn((long) total);
            when(mock.exportConfirmedPage(any(), any(), any(), anyInt())).thenAnswer(invocation -> {
                WaitlistExportCursor after = invocation.getArgument(2);
                int limit = invocation.getArgument(3);
                requestedLimits.add(limit);
                cursors.add(after);
                windows.add(Arrays.asList(invocation.getArgument(0), invocation.getArgument(1)));
                int start = after == null ? 0 : (int) after.id().getLeastSignificantBits() + 1;
                return List.copyOf(rows.subList(Math.min(start, rows.size()), Math.min(start + limit, rows.size())));
            });
        }
    }
}
