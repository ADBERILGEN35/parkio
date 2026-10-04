package com.parkio.gateway.application.waitlist;

import java.util.List;
import reactor.core.publisher.Flux;

/**
 * A confirmed-subscription export: how many rows matched the filter when the export started, the
 * row limit, and the rows, streamed one bounded page at a time and stopping at the limit.
 */
public record WaitlistExport(long matchingRows, int rowLimit, Flux<List<WaitlistExportRow>> pages) {

    /** True when more rows matched than the export returns. */
    public boolean truncated() {
        return matchingRows > rowLimit;
    }
}
